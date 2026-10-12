package br.cefet.sd.middleware.listener;

import br.cefet.sd.comum.protocolo.TipoTransporte;
import br.cefet.sd.middleware.config.Configuracao;
import br.cefet.sd.middleware.protocolo.ProcessadorProtocolo;
import br.cefet.sd.middleware.sessao.DestinoDeSaida;
import br.cefet.sd.middleware.sessao.Distribuidor;
import br.cefet.sd.middleware.sessao.SessaoEnfileirada;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.extensions.DefaultExtension;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.protocols.Protocol;
import org.java_websocket.server.WebSocketServer;

/**
 * Listener WebSocket do Protocolo (porta 9001 por padrao), sobre a
 * biblioteca Java-WebSocket.
 *
 * <p>Cada frame de texto e uma mensagem do Protocolo e vai, sem interpretacao, para
 * {@link ProcessadorProtocolo#processar}; um frame binario e recusado por
 * {@link ProcessadorProtocolo#processarBinario} ({@code FORMATO_INVALIDO}) e a conexao segue aberta.
 * Esta classe nao conhece chaves nem regras do Protocolo.
 *
 * <h2>Origin</h2>
 *
 * No handshake, um cabecalho {@code Origin} presente precisa constar em
 * {@code ws.origens_permitidas} (comparacao sem diferenca de maiusculas e sem a barra final); caso
 * contrario o handshake e recusado e nenhuma Sessao e criada. A ausencia do cabecalho e aceita: ele e
 * enviado por todo navegador (a defesa e contra paginas de outras origens), e Clientes que nao sao
 * navegadores (Desktop, Terceiro) nao o enviam.
 *
 * <h2>Concorrencia</h2>
 *
 * Os callbacks da biblioteca rodam em poucas threads compartilhadas entre conexoes, e o
 * processamento de uma mensagem pode ser demorado (hash de senha, leitura do historico). Por isso
 * cada conexao tem uma fila de entrada limitada ({@value #CAPACIDADE_ENTRADA}) e uma thread virtual
 * propria que a consome em ordem; o callback so enfileira. Se a fila estourar, so aquela conexao e
 * encerrada, com o motivo {@code fila_cheia}.
 *
 * <h2>Encerramento</h2>
 *
 * Quando o frame de fechamento chega, ou a biblioteca detecta conexao perdida, a Sessao e
 * encerrada com {@code fechada_pelo_cliente}, o que fecha o transporte, avisa os observadores
 * (inclusive o {@code desconexao} do Log_Acessos, gravado uma unica vez porque a Sessao so e
 * finalizada uma vez e o primeiro motivo vale) e a remove do {@link Distribuidor}. Uma Sessao que ja
 * estava encerrando por outro motivo ({@code logout}, {@code falhas_de_login}) mantem esse motivo.
 * Conexao que nunca enviou {@code register} ou {@code login} apenas e limpa, sem gravar nada no
 * Log_Acessos, pois nao houve {@code conexao} (ver {@link ListenerTcp}).
 */
public final class ListenerWebSocket implements AutoCloseable {

    /** Tamanho maximo de frame/mensagem aceito pela biblioteca (acima disso, ela fecha com 1009). */
    public static final int TAMANHO_MAXIMO_FRAME = 4 * 1024 * 1024;

    /** Mensagens aguardando processamento, por conexao. */
    static final int CAPACIDADE_ENTRADA = 256;

    private static final String MODULO = "ListenerWebSocket";
    private static final long ESPERA_INICIO_SEGUNDOS = 10;
    private static final int ESPERA_PARADA_MS = 1000;
    private static final long PAUSA_ESCRITA_MS = 2;

    /** Marcadores na fila de entrada (comparados por identidade). */
    private static final Object BINARIO = new Object();

    private static final Object FIM = new Object();

    private final ProcessadorProtocolo processador;
    private final Distribuidor distribuidor;
    private final Set<String> origensPermitidas;
    private final Servidor servidor;
    private final AtomicBoolean iniciado = new AtomicBoolean();
    private final CountDownLatch partida = new CountDownLatch(1);

    private volatile Exception falhaDePartida;

    /**
     * @param porta porta a escutar (0 escolhe uma porta efemera)
     * @param processador recebe o texto das mensagens
     * @param distribuidor de onde a Sessao e removida ao encerrar
     * @param origensPermitidas valores aceitos no cabecalho {@code Origin}
     */
    public ListenerWebSocket(
            int porta,
            ProcessadorProtocolo processador,
            Distribuidor distribuidor,
            Collection<String> origensPermitidas) {
        if (porta < 0 || porta > 65535) {
            throw new IllegalArgumentException("porta fora do intervalo: " + porta);
        }
        this.processador = Objects.requireNonNull(processador, "processador");
        this.distribuidor = Objects.requireNonNull(distribuidor, "distribuidor");
        this.origensPermitidas = Objects.requireNonNull(origensPermitidas, "origensPermitidas")
                .stream()
                .map(ListenerWebSocket::normalizarOrigem)
                .collect(Collectors.toUnmodifiableSet());
        this.servidor = new Servidor(porta);
    }

    /** Listener na {@code porta.ws} e com as {@code ws.origens_permitidas} da configuracao. */
    public ListenerWebSocket(
            Configuracao configuracao, ProcessadorProtocolo processador, Distribuidor distribuidor) {
        this(
                Objects.requireNonNull(configuracao, "configuracao").portaWs(),
                processador,
                distribuidor,
                configuracao.wsOrigensPermitidas());
    }

    /**
     * Abre a porta e comeca a aceitar conexoes; so retorna depois que a porta esta aberta.
     *
     * @throws IOException se a porta nao puder ser aberta
     * @throws IllegalStateException se ja foi iniciado
     */
    public void iniciar() throws IOException {
        if (!iniciado.compareAndSet(false, true)) {
            throw new IllegalStateException("Listener WebSocket ja iniciado");
        }
        servidor.start();
        try {
            if (!partida.await(ESPERA_INICIO_SEGUNDOS, TimeUnit.SECONDS)) {
                pararQuieto();
                throw new IOException("Listener WebSocket nao iniciou a tempo");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pararQuieto();
            throw new InterruptedIOException("Interrompido ao iniciar o Listener WebSocket");
        }
        Exception falha = falhaDePartida;
        if (falha != null) {
            pararQuieto();
            throw new IOException("Falha ao abrir a porta do Listener WebSocket", falha);
        }
    }

    /** Porta em que o listener escuta (util quando criado com porta 0). */
    public int portaLocal() {
        return servidor.getPort();
    }

    /** Para de aceitar conexoes e encerra as que estao abertas. Idempotente. */
    @Override
    public void close() {
        pararQuieto();
    }

    private void pararQuieto() {
        try {
            servidor.stop(ESPERA_PARADA_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // Nao chegou a iniciar ou ja foi parado.
        }
    }

    /** Forma comparavel de uma origem: sem espacos nas pontas, minuscula, sem barra final. */
    static String normalizarOrigem(String origem) {
        String o = origem.strip().toLowerCase(Locale.ROOT);
        while (o.endsWith("/")) {
            o = o.substring(0, o.length() - 1);
        }
        return o;
    }

    /** {@code true} se a origem consta na lista; origem ausente ({@code null}) e aceita. */
    boolean origemPermitida(String origem) {
        return origem == null || origensPermitidas.contains(normalizarOrigem(origem));
    }

    // ------------------------------------------------------------------
    // Estado por conexao
    // ------------------------------------------------------------------

    private static final class Conexao {
        final SessaoEnfileirada sessao;
        final BlockingQueue<Object> entradas = new ArrayBlockingQueue<>(CAPACIDADE_ENTRADA);

        Conexao(SessaoEnfileirada sessao) {
            this.sessao = sessao;
        }

        /** Descarta o que ainda nao foi processado e manda a thread de entrada terminar. */
        void encerrarEntrada() {
            entradas.clear();
            if (!entradas.offer(FIM)) {
                entradas.clear();
                entradas.offer(FIM);
            }
        }
    }

    /** Consome, em ordem, as mensagens de uma conexao. Roda em uma thread virtual por conexao. */
    private void processarEntradas(Conexao conexao) {
        try {
            while (true) {
                Object item = conexao.entradas.take();
                if (item == FIM) {
                    return;
                }
                if (conexao.sessao.fechada()) {
                    continue; // Sessao em encerramento: nao processa mais nada
                }
                try {
                    if (item == BINARIO) {
                        processador.processarBinario(conexao.sessao);
                    } else {
                        processador.processar(conexao.sessao, (String) item);
                    }
                } catch (RuntimeException e) {
                    // O processador nao deve lancar; se lancar, so esta mensagem e perdida.
                    processador.registrarErro(MODULO + ".processar", e, conexao.sessao);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Servidor da biblioteca
    // ------------------------------------------------------------------

    private final class Servidor extends WebSocketServer {

        Servidor(int porta) {
            super(new InetSocketAddress(porta), List.of(rascunho()));
            setReuseAddr(true);
        }

        @Override
        public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
                WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
            ServerHandshakeBuilder resposta =
                    super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
            String origem = request.hasFieldValue("Origin") ? request.getFieldValue("Origin") : null;
            if (!origemPermitida(origem)) {
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Origin nao permitida");
            }
            return resposta;
        }

        @Override
        public void onStart() {
            partida.countDown();
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            InetSocketAddress remoto = conn.getRemoteSocketAddress();
            String endereco = remoto != null && remoto.getAddress() != null
                    ? remoto.getAddress().getHostAddress()
                    : null;
            SessaoEnfileirada sessao =
                    SessaoEnfileirada.abrir(TipoTransporte.WEBSOCKET, endereco, new DestinoWebSocket(conn));
            Conexao conexao = new Conexao(sessao);
            conn.setAttachment(conexao);
            try {
                Thread.ofVirtual().name("conexao-ws").start(() -> processarEntradas(conexao));
            } catch (RuntimeException | Error e) {
                sessao.fechar(SessaoEnfileirada.MOTIVO_FECHADA_PELO_CLIENTE);
                throw e;
            }
        }

        @Override
        public void onMessage(WebSocket conn, String mensagem) {
            enfileirar(conn, mensagem);
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer bytes) {
            enfileirar(conn, BINARIO);
        }

        private void enfileirar(WebSocket conn, Object item) {
            Conexao conexao = conn.getAttachment();
            if (conexao == null || conexao.sessao.fechada()) {
                return;
            }
            if (!conexao.entradas.offer(item)) {
                // Cliente inunda mais rapido do que o processamento: encerra so esta conexao.
                conexao.encerrarEntrada();
                conexao.sessao.fechar(SessaoEnfileirada.MOTIVO_FILA_CHEIA);
            }
        }

        @Override
        public void onClose(WebSocket conn, int codigo, String motivo, boolean remoto) {
            Conexao conexao = conn.getAttachment();
            if (conexao == null) {
                return; // handshake recusado: nunca houve Sessao
            }
            conexao.encerrarEntrada();
            // Se a Sessao ja estava encerrando por outro motivo, ele prevalece: a thread escritora
            // termina sozinha (o transporte ja fechou, entao a escrita falha ou o marcador de fim chega).
            if (!conexao.sessao.fechada()) {
                conexao.sessao.fechar(SessaoEnfileirada.MOTIVO_FECHADA_PELO_CLIENTE);
            }
            distribuidor.remover(conexao.sessao); // idempotente; a Sessao ja se remove ao fechar
        }

        @Override
        public void onError(WebSocket conn, Exception erro) {
            if (conn == null) {
                // Erro do servidor: durante a partida (porta ocupada) falha o iniciar().
                if (partida.getCount() > 0) {
                    falhaDePartida = erro;
                    partida.countDown();
                } else {
                    processador.registrarErro(MODULO, erro, null);
                }
                return;
            }
            // Handshake recusado e quedas de rede de um Cliente sao esperados; nao poluem o Log_Erros.
            if (erro instanceof InvalidDataException || erro instanceof IOException) {
                return;
            }
            Conexao conexao = conn.getAttachment();
            processador.registrarErro(MODULO, erro, conexao == null ? null : conexao.sessao);
        }
    }

    private static Draft rascunho() {
        // Mesmas listas do construtor padrao da biblioteca (extensao e protocolo "vazios"); listas
        // realmente vazias fariam a negociacao do handshake falhar sempre.
        return new Draft_6455(
                Collections.singletonList(new DefaultExtension()),
                Collections.singletonList(new Protocol("")),
                TAMANHO_MAXIMO_FRAME);
    }

    // ------------------------------------------------------------------
    // Saida
    // ------------------------------------------------------------------

    /** Lado de escrita: um frame de texto por quadro. */
    private static final class DestinoWebSocket implements DestinoDeSaida {
        private final WebSocket conn;

        DestinoWebSocket(WebSocket conn) {
            this.conn = conn;
        }

        @Override
        public void escrever(String json) throws IOException {
            // A biblioteca guarda sem limite o que ainda nao saiu para a rede. Esperar o escoamento
            // aqui (na escritora desta Sessao) faz a fila da Sessao encher para um Cliente lento,
            // o que ativa o encerramento por fila_cheia em vez de acumular memoria.
            try {
                while (conn.hasBufferedData() && conn.isOpen()) {
                    Thread.sleep(PAUSA_ESCRITA_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Escrita interrompida");
            }
            try {
                conn.send(json); // lanca WebsocketNotConnectedException se ja fechou
            } catch (RuntimeException e) {
                throw new IOException("Falha ao enviar o frame", e);
            }
        }

        @Override
        public void fechar() {
            try {
                conn.close(CloseFrame.NORMAL);
            } catch (RuntimeException e) {
                // Ja fechada.
            }
        }
    }
}
