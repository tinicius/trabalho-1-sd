package br.cefet.sd.middleware.listener;

import br.cefet.sd.comum.protocolo.LeitorDeLinhas;
import br.cefet.sd.comum.protocolo.TipoTransporte;
import br.cefet.sd.middleware.config.Configuracao;
import br.cefet.sd.middleware.protocolo.ProcessadorProtocolo;
import br.cefet.sd.middleware.sessao.DestinoDeSaida;
import br.cefet.sd.middleware.sessao.Distribuidor;
import br.cefet.sd.middleware.sessao.Sessao;
import br.cefet.sd.middleware.sessao.SessaoEnfileirada;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listener TCP do Protocolo (porta 9000 por padrao).
 *
 * <p>Aceita conexoes e atende cada uma em uma thread virtual propria: le bytes do socket, separa
 * linhas com o {@link LeitorDeLinhas} e entrega cada linha ao
 * {@link ProcessadorProtocolo#processarLinha}. Esta classe so repassa texto: nao interpreta o
 * Protocolo e nao conhece chaves. A escrita fica a cargo da thread escritora da
 * {@link SessaoEnfileirada}, de modo que um Cliente lento nao atrasa os demais.
 *
 * <h2>Encerramento de uma conexao</h2>
 *
 * Toda conexao termina por um unico caminho ({@code encerrar}), seja ela autenticada ou nao:
 *
 * <ul>
 *   <li>Sem dados por {@link #TIMEOUT_LEITURA} ({@code SO_TIMEOUT}): motivo {@code timeout}.
 *   <li>Fim do fluxo ou erro de rede: motivo {@code fechada_pelo_cliente}.
 *   <li>Se a Sessao ja estava em encerramento por outro motivo ({@code logout}, {@code fila_cheia},
 *       {@code falhas_de_login}), esse motivo prevalece: a thread de leitura para de processar linhas
 *       e espera (por ate {@link #ESPERA_ENCERRAMENTO}) a thread escritora entregar o que ficou na
 *       fila e finalizar a Sessao, de modo que a resposta final nao se perca.
 * </ul>
 *
 * Encerrar a Sessao ({@link Sessao#fechar}) fecha o socket, avisa os observadores (inclusive o que
 * descarta o estado da conexao no processador) e remove a Sessao do {@link Distribuidor}. Como a
 * Sessao e finalizada uma unica vez e o primeiro motivo vale, o evento {@code desconexao} do
 * Log_Acessos, que {@code OperacoesAutenticacao.registrarConexao} arma como observador da Sessao, e
 * gravado no maximo uma vez por conexao. Esse evento so existe para conexoes que chegaram a enviar
 * {@code register} ou {@code login} (as unicas para as quais {@code conexao} foi gravado); uma
 * conexao que nunca o fez e apenas limpa, sem gravar no Log_Acessos, para que
 * {@code conexao}/{@code desconexao} formem sempre pares.
 */
public final class ListenerTcp implements AutoCloseable {

    /** Tempo maximo sem receber nenhum byte antes de encerrar a conexao. */
    public static final Duration TIMEOUT_LEITURA = Duration.ofSeconds(90);

    /** Quanto a thread de leitura espera a finalizacao de uma Sessao que ja estava encerrando. */
    static final Duration ESPERA_ENCERRAMENTO = Duration.ofSeconds(10);

    static final String MOTIVO_TIMEOUT = "timeout";

    private static final String MODULO = "ListenerTcp";
    private static final int TAMANHO_BUFFER = 8192;
    private static final long PAUSA_APOS_FALHA_DE_ACEITE_MS = 100;

    private final int porta;
    private final ProcessadorProtocolo processador;
    private final Distribuidor distribuidor;
    private final Duration timeoutLeitura;
    private final Set<Socket> conexoesAtivas = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean fechado = new AtomicBoolean();

    private volatile ServerSocket servidor;
    private Thread aceitador;

    /** Listener na porta {@code porta} (0 escolhe uma porta efemera) com o timeout padrao de 90 s. */
    public ListenerTcp(int porta, ProcessadorProtocolo processador, Distribuidor distribuidor) {
        this(porta, processador, distribuidor, TIMEOUT_LEITURA);
    }

    /** Listener na porta {@code porta}, com {@code SO_TIMEOUT} informado (util em testes). */
    public ListenerTcp(
            int porta,
            ProcessadorProtocolo processador,
            Distribuidor distribuidor,
            Duration timeoutLeitura) {
        if (porta < 0 || porta > 65535) {
            throw new IllegalArgumentException("porta fora do intervalo: " + porta);
        }
        Objects.requireNonNull(timeoutLeitura, "timeoutLeitura");
        if (timeoutLeitura.isZero() || timeoutLeitura.isNegative()
                || timeoutLeitura.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("timeoutLeitura invalido");
        }
        this.porta = porta;
        this.processador = Objects.requireNonNull(processador, "processador");
        this.distribuidor = Objects.requireNonNull(distribuidor, "distribuidor");
        this.timeoutLeitura = timeoutLeitura;
    }

    /** Listener na {@code porta.tcp} da configuracao. */
    public ListenerTcp(
            Configuracao configuracao, ProcessadorProtocolo processador, Distribuidor distribuidor) {
        this(Objects.requireNonNull(configuracao, "configuracao").portaTcp(), processador, distribuidor);
    }

    /**
     * Abre a porta e comeca a aceitar conexoes.
     *
     * @throws IOException se a porta nao puder ser aberta
     * @throws IllegalStateException se ja foi iniciado ou fechado
     */
    public synchronized void iniciar() throws IOException {
        if (fechado.get() || servidor != null) {
            throw new IllegalStateException("Listener TCP ja iniciado ou fechado");
        }
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(porta));
        } catch (IOException | RuntimeException e) {
            ss.close();
            throw e;
        }
        servidor = ss;
        aceitador = new Thread(() -> aceitar(ss), "listener-tcp-aceitador");
        aceitador.start();
    }

    /** Porta em que o listener escuta (util quando criado com porta 0). */
    public int portaLocal() {
        ServerSocket ss = servidor;
        if (ss == null) {
            throw new IllegalStateException("Listener TCP nao iniciado");
        }
        return ss.getLocalPort();
    }

    /** Para de aceitar conexoes e encerra as que estao abertas. Idempotente. */
    @Override
    public void close() {
        if (!fechado.compareAndSet(false, true)) {
            return;
        }
        ServerSocket ss = servidor;
        if (ss != null) {
            fecharQuieto(ss);
        }
        for (Socket socket : List.copyOf(conexoesAtivas)) {
            fecharQuieto(socket);
        }
    }

    // ------------------------------------------------------------------
    // Aceite
    // ------------------------------------------------------------------

    private void aceitar(ServerSocket ss) {
        while (!fechado.get()) {
            Socket socket;
            try {
                socket = ss.accept();
            } catch (IOException e) {
                if (fechado.get() || ss.isClosed()) {
                    return;
                }
                // Falha transitoria (por exemplo, descritores esgotados): registra e tenta de novo.
                processador.registrarErro(MODULO + ".aceitar", e, null);
                try {
                    Thread.sleep(PAUSA_APOS_FALHA_DE_ACEITE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            conexoesAtivas.add(socket);
            try {
                Thread.ofVirtual().name("conexao-tcp").start(() -> atender(socket));
            } catch (RuntimeException | Error e) {
                conexoesAtivas.remove(socket);
                fecharQuieto(socket);
            }
            if (fechado.get()) {
                fecharQuieto(socket); // corrida com close(): garante que nao fica aberta
            }
        }
    }

    // ------------------------------------------------------------------
    // Atendimento de uma conexao
    // ------------------------------------------------------------------

    private void atender(Socket socket) {
        SessaoEnfileirada sessao = null;
        String motivo = SessaoEnfileirada.MOTIVO_FECHADA_PELO_CLIENTE;
        CountDownLatch finalizada = new CountDownLatch(1);
        try {
            socket.setSoTimeout((int) timeoutLeitura.toMillis());
            socket.setTcpNoDelay(true);
            sessao = SessaoEnfileirada.abrir(
                    TipoTransporte.TCP, enderecoDe(socket), new DestinoTcp(socket));
            sessao.aoFechar(m -> finalizada.countDown());
            lerEProcessar(socket, sessao);
        } catch (SocketTimeoutException e) {
            motivo = MOTIVO_TIMEOUT;
        } catch (IOException e) {
            motivo = SessaoEnfileirada.MOTIVO_FECHADA_PELO_CLIENTE;
        } catch (RuntimeException e) {
            processador.registrarErro(MODULO + ".atender", e, sessao);
        } finally {
            encerrar(socket, sessao, motivo, finalizada);
        }
    }

    /** Laco de leitura: bytes -> linhas -> {@link ProcessadorProtocolo}. Sai por excecao ou fim. */
    private void lerEProcessar(Socket socket, SessaoEnfileirada sessao) throws IOException {
        InputStream entrada = socket.getInputStream();
        LeitorDeLinhas leitor = new LeitorDeLinhas();
        byte[] buffer = new byte[TAMANHO_BUFFER];
        while (!sessao.fechada()) {
            int lidos = entrada.read(buffer); // SocketTimeoutException apos o SO_TIMEOUT
            if (lidos < 0) {
                return; // fim do fluxo: o Cliente fechou
            }
            for (LeitorDeLinhas.Linha linha : leitor.alimentar(buffer, 0, lidos)) {
                if (sessao.fechada()) {
                    return; // Sessao em encerramento: o resto do que chegou e descartado
                }
                try {
                    processador.processarLinha(sessao, linha);
                } catch (RuntimeException e) {
                    // processarLinha nao deve lancar; se lancar, so esta linha e perdida.
                    processador.registrarErro(MODULO + ".processarLinha", e, sessao);
                }
            }
        }
    }

    /** Unico caminho de saida de uma conexao: Sessao finalizada, fora do Distribuidor, socket fechado. */
    private void encerrar(
            Socket socket, SessaoEnfileirada sessao, String motivo, CountDownLatch finalizada) {
        try {
            if (sessao != null) {
                if (!sessao.fechada()) {
                    sessao.fechar(motivo);
                } else if (!aguardar(finalizada)) {
                    sessao.fechar(motivo); // a escrita travou: nao deixa a conexao presa
                }
                distribuidor.remover(sessao); // idempotente; a propria Sessao ja o faz ao fechar
            }
        } catch (RuntimeException e) {
            processador.registrarErro(MODULO + ".encerrar", e, sessao);
        } finally {
            conexoesAtivas.remove(socket);
            fecharQuieto(socket);
        }
    }

    private static boolean aguardar(CountDownLatch finalizada) {
        try {
            return finalizada.await(ESPERA_ENCERRAMENTO.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String enderecoDe(Socket socket) {
        return socket.getRemoteSocketAddress() instanceof InetSocketAddress isa
                        && isa.getAddress() != null
                ? isa.getAddress().getHostAddress()
                : null;
    }

    private static void fecharQuieto(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException | RuntimeException e) {
            // Ja estava fechado ou o sistema operacional recusou; nada a fazer.
        }
    }

    // ------------------------------------------------------------------
    // Saida
    // ------------------------------------------------------------------

    /** Lado de escrita: o quadro seguido de {@code \n}, em uma unica escrita. */
    private static final class DestinoTcp implements DestinoDeSaida {
        private final Socket socket;
        private final OutputStream saida;

        DestinoTcp(Socket socket) throws IOException {
            this.socket = socket;
            this.saida = socket.getOutputStream();
        }

        @Override
        public void escrever(String json) throws IOException {
            byte[] quadro = (json + "\n").getBytes(StandardCharsets.UTF_8);
            saida.write(quadro);
            saida.flush();
        }

        @Override
        public void fechar() {
            try {
                socket.shutdownOutput(); // FIN depois dos dados ja escritos
            } catch (IOException | RuntimeException e) {
                // Socket ja fechado ou sem saida: o close() abaixo resolve.
            }
            fecharQuieto(socket);
        }
    }
}
