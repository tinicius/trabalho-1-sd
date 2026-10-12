package br.cefet.sd.cliente.desktop.rede;

import br.cefet.sd.cliente.desktop.rede.EventoConexao.EventoErro;
import br.cefet.sd.cliente.desktop.rede.EventoConexao.EventoEstado;
import br.cefet.sd.cliente.desktop.rede.EventoConexao.EventoHistorico;
import br.cefet.sd.cliente.desktop.rede.EventoConexao.EventoMensagem;
import br.cefet.sd.cliente.desktop.rede.EventoConexao.EventoReautenticado;
import br.cefet.sd.comum.cifra.Chave;
import br.cefet.sd.comum.cifra.CifraManual;
import br.cefet.sd.comum.cifra.DecifragemFalhouException;
import br.cefet.sd.comum.protocolo.CodigoErro;
import br.cefet.sd.comum.protocolo.ErroDeProtocoloException;
import br.cefet.sd.comum.protocolo.LeitorDeLinhas;
import br.cefet.sd.comum.protocolo.MensagemChat;
import br.cefet.sd.comum.protocolo.Perfil;
import br.cefet.sd.comum.protocolo.ProtocoloJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntToDoubleFunction;

/**
 * Conexao TCP do Cliente_Desktop com o Middleware (porta 9000).
 *
 * <p>Comportamento identico ao de {@code cliente-web/js/conexao.js} e
 * {@code cliente-python/comunicador/nucleo/conexao.py}:
 *
 * <ul>
 *   <li>um objeto JSON por linha ({@link LeitorDeLinhas}), UTF-8, lido por uma thread daemon;
 *   <li>{@code dados} de {@code register} e {@code login} e {@code conteudo} de {@code send_message}
 *       sao cifrados com a Chave_Transito pela Cifra_Manual; mensagens e historico recebidos sao
 *       decifrados aqui, antes de chegarem a interface;
 *   <li>{@code ping} a cada 30 s enquanto a conexao esta aberta;
 *   <li>reconexao automatica com atraso exponencial limitado e jitter de +-20% ({@link
 *       PoliticaReconexao}), ate 10 tentativas consecutivas; depois, estado "desconectado" e {@link
 *       #tentarNovamente()};
 *   <li>login e senha ficam <b>somente na memoria do processo</b> (nunca em disco) para o relogin
 *       automatico e sao apagados no {@link #logout()} e no {@link #fechar()}.
 * </ul>
 *
 * <p>Os metodos publicos nao bloqueiam: devolvem {@link CompletableFuture} ou publicam eventos no
 * ouvinte (veja {@link EventoConexao}). Falhas chegam como {@link ErroRequisicao} (causa da
 * {@code ExecutionException}).
 */
public final class ConexaoTcp {

    /** Porta TCP do Middleware ({@code porta.tcp}). */
    public static final int PORTA_TCP = 9000;

    /** Intervalo entre pings (keep-alive), em ms. */
    public static final long INTERVALO_PING_MS = 30_000L;

    /** Tempo maximo de espera por uma resposta, em ms. */
    public static final long TEMPO_LIMITE_REQUISICAO_MS = 15_000L;

    /** Tempo maximo para abrir o socket, em ms. */
    public static final long TEMPO_LIMITE_CONEXAO_MS = 5_000L;

    /** Periodo com que a thread de rede reavalia ping, prazos e encerramento, em ms. */
    private static final int SONDA_MS = 500;

    // ------------------------------------------------------------------
    // Politica de reconexao (pura; delegada a PoliticaReconexao)
    // ------------------------------------------------------------------

    /** {@code min(1000 * 2^n, 30000)} ms, sem jitter. Veja {@link PoliticaReconexao#atrasoReconexao}. */
    public static long atrasoReconexao(int n) {
        return PoliticaReconexao.atrasoReconexao(n);
    }

    /** Atraso com jitter de +-20% ({@code aleatorio} em [0, 1]). */
    public static double atrasoComJitter(int n, double aleatorio) {
        return PoliticaReconexao.atrasoComJitter(n, aleatorio);
    }

    /** Verdadeiro se cabe mais uma tentativa depois de {@code feitas} (limite de 10). */
    public static boolean podeTentarNovamente(int feitas) {
        return PoliticaReconexao.podeTentarNovamente(feitas);
    }

    // ------------------------------------------------------------------
    // Opcoes
    // ------------------------------------------------------------------

    /** Abre o socket; injetavel para testes. */
    @FunctionalInterface
    public interface FabricaSocket {
        Socket abrir(String host, int porta, int tempoLimiteMs) throws IOException;
    }

    /** Resultado do login: identificador da Sessao e Perfil da conta. */
    public record ResultadoLogin(String sessao, Perfil perfil) {}

    /** Resultado do envio de uma mensagem: identificador e instante atribuidos pelo Middleware. */
    public record ResultadoEnvio(String mid, String ts) {}

    /** Parametros da conexao. Use {@link #padrao()} e os metodos {@code com...}. */
    public record Opcoes(
            String host,
            int porta,
            String tipoCliente,
            String versaoCliente,
            long intervaloPingMs,
            long tempoLimiteRequisicaoMs,
            long tempoLimiteConexaoMs,
            IntToDoubleFunction atrasoMs,
            FabricaSocket fabricaSocket) {

        public static Opcoes padrao() {
            return new Opcoes(
                    "localhost",
                    PORTA_TCP,
                    "desktop",
                    "1",
                    INTERVALO_PING_MS,
                    TEMPO_LIMITE_REQUISICAO_MS,
                    TEMPO_LIMITE_CONEXAO_MS,
                    n -> atrasoComJitter(n, ThreadLocalRandom.current().nextDouble()),
                    ConexaoTcp::abrirSocketPadrao);
        }

        public Opcoes comEndereco(String novoHost, int novaPorta) {
            return new Opcoes(novoHost, novaPorta, tipoCliente, versaoCliente, intervaloPingMs,
                    tempoLimiteRequisicaoMs, tempoLimiteConexaoMs, atrasoMs, fabricaSocket);
        }

        public Opcoes comIntervaloPingMs(long ms) {
            return new Opcoes(host, porta, tipoCliente, versaoCliente, ms, tempoLimiteRequisicaoMs,
                    tempoLimiteConexaoMs, atrasoMs, fabricaSocket);
        }

        public Opcoes comTempoLimiteRequisicaoMs(long ms) {
            return new Opcoes(host, porta, tipoCliente, versaoCliente, intervaloPingMs, ms,
                    tempoLimiteConexaoMs, atrasoMs, fabricaSocket);
        }

        public Opcoes comTempoLimiteConexaoMs(long ms) {
            return new Opcoes(host, porta, tipoCliente, versaoCliente, intervaloPingMs,
                    tempoLimiteRequisicaoMs, ms, atrasoMs, fabricaSocket);
        }

        /** Funcao que devolve o atraso (ms) antes da tentativa {@code n}; padrao: com jitter. */
        public Opcoes comAtrasoMs(IntToDoubleFunction funcao) {
            return new Opcoes(host, porta, tipoCliente, versaoCliente, intervaloPingMs,
                    tempoLimiteRequisicaoMs, tempoLimiteConexaoMs, funcao, fabricaSocket);
        }

        public Opcoes comFabricaSocket(FabricaSocket fabrica) {
            return new Opcoes(host, porta, tipoCliente, versaoCliente, intervaloPingMs,
                    tempoLimiteRequisicaoMs, tempoLimiteConexaoMs, atrasoMs, fabrica);
        }
    }

    private static Socket abrirSocketPadrao(String host, int porta, int tempoLimiteMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, porta), tempoLimiteMs);
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignorada) {
                // nada a fazer
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Estado interno
    // ------------------------------------------------------------------

    /** Sinal de parada de um ciclo da thread de rede (equivalente a um Event). */
    private static final class Parada {
        private final CountDownLatch trava = new CountDownLatch(1);

        void acionar() {
            trava.countDown();
        }

        boolean acionada() {
            return trava.getCount() == 0;
        }

        /** Espera ate {@code ms}; verdadeiro se a parada foi acionada. */
        boolean aguardar(long ms) throws InterruptedException {
            return trava.await(ms, TimeUnit.MILLISECONDS);
        }
    }

    private static final class Pendente {
        final CompletableFuture<ObjectNode> futuro;
        volatile long prazoNanos;

        Pendente(CompletableFuture<ObjectNode> futuro, long prazoNanos) {
            this.futuro = futuro;
            this.prazoNanos = prazoNanos;
        }
    }

    private record Credenciais(String login, String senha) {
        @Override
        public String toString() {
            return "Credenciais[]";
        }
    }

    private final Chave chave;
    private final String dispositivo;
    private final Consumer<EventoConexao> ouvinte;
    private final Opcoes opcoes;
    private final ThreadPoolExecutor despachante;

    // Ordem de aquisicao: lockEnvio -> lock. Futuros nunca sao concluidos com algum lock tomado.
    private final Object lock = new Object();
    private final java.util.concurrent.locks.ReentrantLock lockEnvio =
            new java.util.concurrent.locks.ReentrantLock();

    private volatile EstadoConexao estado = EstadoConexao.DESCONECTADO;
    private volatile Socket sock;
    private Map<String, Pendente> pendentes = new HashMap<>();
    private CompletableFuture<Void> abertura;
    private int tentativas;
    private Credenciais credenciais; // somente em memoria
    private String motivoFinal;
    private boolean ativa;
    private Parada parar = new Parada();

    /**
     * @param chaveTransito Chave_Transito lida por {@code ConfigCliente}
     * @param dispositivo UUID do dispositivo ({@code dispositivo.id})
     * @param ouvinte recebe os eventos, em ordem, em uma thread da conexao (pode ser {@code null})
     */
    public ConexaoTcp(Chave chaveTransito, String dispositivo, Consumer<EventoConexao> ouvinte) {
        this(chaveTransito, dispositivo, ouvinte, Opcoes.padrao());
    }

    public ConexaoTcp(Chave chaveTransito, String dispositivo, Consumer<EventoConexao> ouvinte, Opcoes opcoes) {
        if (chaveTransito == null) {
            throw new IllegalArgumentException("Chave_Transito ausente");
        }
        if (dispositivo == null || dispositivo.isBlank()) {
            throw new IllegalArgumentException("Dispositivo ausente");
        }
        this.chave = chaveTransito;
        this.dispositivo = dispositivo;
        this.ouvinte = ouvinte;
        this.opcoes = opcoes;
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), tarefa -> {
                    Thread t = new Thread(tarefa, "comunicador-eventos");
                    t.setDaemon(true);
                    return t;
                });
        executor.allowCoreThreadTimeOut(true);
        this.despachante = executor;
    }

    @Override
    public String toString() { // nunca expoe chave nem credenciais
        return "ConexaoTcp[" + opcoes.host() + ":" + opcoes.porta() + ", estado=" + estado + "]";
    }

    // ------------------------------------------------------------------
    // Consulta
    // ------------------------------------------------------------------

    public EstadoConexao estado() {
        return estado;
    }

    /** Tentativas consecutivas de reconexao ja realizadas. */
    public int tentativas() {
        synchronized (lock) {
            return tentativas;
        }
    }

    public boolean aberta() {
        return sock != null;
    }

    /** Verdadeiro se ha login e senha em memoria (relogin automatico possivel). */
    public boolean temCredenciais() {
        synchronized (lock) {
            return credenciais != null;
        }
    }

    // ------------------------------------------------------------------
    // API publica
    // ------------------------------------------------------------------

    /**
     * Abre o socket em segundo plano. O futuro conclui quando aberto e falha com {@link
     * ErroRequisicao} se a abertura falhar.
     */
    public CompletableFuture<Void> conectar() {
        synchronized (lock) {
            if (sock != null) {
                return CompletableFuture.completedFuture(null);
            }
            if (ativa) {
                if (abertura != null && !abertura.isDone()) {
                    return abertura;
                }
                return CompletableFuture.failedFuture(
                        new ErroRequisicao(ErroRequisicao.SEM_CONEXAO, "Reconexao em andamento"));
            }
            CompletableFuture<Void> nova = new CompletableFuture<>();
            abertura = nova;
            definirEstado(EstadoConexao.CONECTANDO, Map.of());
            iniciarThread(nova, false, false);
            return nova;
        }
    }

    /**
     * Cadastro. {@code dados} e a cifra (Transito) do JSON {@code {login, apelido, senha, avatar,
     * cor}}. Abre a conexao se preciso. O futuro traz a resposta {@code ok:true}.
     */
    public CompletableFuture<ObjectNode> registrar(
            String login, String apelido, String senha, String avatar, String cor) {
        return emSegundoPlano(() -> {
            garantirConexao();
            ObjectNode campos = JsonNodeFactory.instance.objectNode();
            campos.put("login", login);
            campos.put("apelido", apelido);
            campos.put("senha", senha);
            campos.put("avatar", avatar);
            campos.put("cor", cor);
            String id = novoId("reg");
            String dados = CifraManual.cifrar(chave, ProtocoloJson.serializar(campos));
            return aguardar(requisitar(id, ProtocoloJson.montarRegister(id, dados)));
        });
    }

    /**
     * Login. {@code dados} e a cifra (Transito) do JSON {@code {login, senha}}. Em caso de sucesso,
     * guarda login e senha somente em memoria para o relogin automatico.
     */
    public CompletableFuture<ResultadoLogin> login(String login, String senha) {
        return emSegundoPlano(() -> loginSincrono(login, senha));
    }

    /** {@code send_message} com {@code conteudo} cifrado (texto ou identificador de figurinha). */
    public CompletableFuture<ResultadoEnvio> enviarMensagem(MensagemChat.Tipo tipo, String conteudo) {
        String id = novoId("msg");
        ObjectNode quadro;
        try {
            quadro = ProtocoloJson.montarSendMessage(id, tipo, CifraManual.cifrar(chave, conteudo));
        } catch (IllegalArgumentException | NullPointerException e) {
            return CompletableFuture.failedFuture(
                    new ErroRequisicao(CodigoErro.CAMPO_INVALIDO.name(), "Conteudo invalido"));
        }
        return requisitar(id, quadro).thenApply(r -> new ResultadoEnvio(texto(r, "mid"), texto(r, "ts")));
    }

    /**
     * {@code history}. Os lotes chegam como {@link EventoHistorico}; o futuro conclui ao receber o
     * ultimo lote.
     */
    public CompletableFuture<ObjectNode> solicitarHistorico(String desde) {
        String id = novoId("his");
        return requisitar(id, ProtocoloJson.montarHistory(id, desde));
    }

    /** {@code error_report} (nao exige login). O futuro traz a lista de {@code evid} aceitos. */
    public CompletableFuture<List<String>> enviarErrorReport(List<ObjectNode> eventos) {
        String id = novoId("err");
        ObjectNode quadro;
        try {
            quadro = ProtocoloJson.montarErrorReport(id, eventos);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(
                    new ErroRequisicao(CodigoErro.CAMPO_INVALIDO.name(), "Requisicao invalida"));
        }
        return requisitar(id, quadro).thenApply(r -> {
            List<String> aceitos = new ArrayList<>();
            JsonNode lista = r.get("aceitos");
            if (lista != null && lista.isArray()) {
                lista.forEach(no -> {
                    if (no.isTextual()) {
                        aceitos.add(no.textValue());
                    }
                });
            }
            return aceitos;
        });
    }

    /** Avisa o Middleware, apaga as credenciais da memoria e nao reconecta. */
    public CompletableFuture<Void> logout() {
        return emSegundoPlano(this::logoutSincrono);
    }

    /** Fecha a conexao sem reconectar e apaga as credenciais da memoria. */
    public void fechar() {
        Socket antigo;
        CompletableFuture<Void> aberturaPendente;
        lockEnvio.lock();
        try {
            synchronized (lock) {
                parar.acionar();
                credenciais = null;
                tentativas = 0;
                ativa = false;
                antigo = sock;
                sock = null;
                aberturaPendente = abertura;
                abertura = null;
            }
        } finally {
            lockEnvio.unlock();
        }
        if (antigo != null) {
            derrubar(antigo);
        }
        if (aberturaPendente != null && !aberturaPendente.isDone()) {
            aberturaPendente.completeExceptionally(
                    new ErroRequisicao(ErroRequisicao.CONEXAO_PERDIDA, "Conexao encerrada"));
        }
        rejeitarPendentes(new ErroRequisicao(ErroRequisicao.CONEXAO_PERDIDA, "Conexao encerrada"));
        synchronized (lock) {
            definirEstado(EstadoConexao.DESCONECTADO, Map.of("motivo", "encerrado"));
        }
    }

    /**
     * Depois de esgotar as tentativas (estado "desconectado" com credenciais em memoria), zera o
     * contador e reconecta de novo. Devolve {@code false} se nao havia o que retomar.
     */
    public boolean tentarNovamente() {
        synchronized (lock) {
            if (credenciais == null || ativa) {
                return false;
            }
            tentativas = 0;
            iniciarThread(null, true, true);
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Operacoes compostas (rodam fora da thread da interface)
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface Operacao<T> {
        T executar() throws ErroRequisicao;
    }

    private <T> CompletableFuture<T> emSegundoPlano(Operacao<T> operacao) {
        CompletableFuture<T> futuro = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                futuro.complete(operacao.executar());
            } catch (ErroRequisicao e) {
                futuro.completeExceptionally(e);
            } catch (RuntimeException e) {
                futuro.completeExceptionally(
                        new ErroRequisicao(CodigoErro.ERRO_INTERNO.name(), CodigoErro.ERRO_INTERNO.mensagem()));
            }
        }, "comunicador-op");
        t.setDaemon(true);
        t.start();
        return futuro;
    }

    /** Espera um futuro de requisicao (ele mesmo tem prazo); converte falhas em ErroRequisicao. */
    private <T> T aguardar(CompletableFuture<T> futuro) throws ErroRequisicao {
        try {
            return futuro.get(opcoes.tempoLimiteRequisicaoMs() + opcoes.tempoLimiteConexaoMs() + 5_000L,
                    TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw comoErroRequisicao(e.getCause());
        } catch (TimeoutException e) {
            throw new ErroRequisicao(ErroRequisicao.TEMPO_ESGOTADO, "O servidor nao respondeu a tempo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErroRequisicao(ErroRequisicao.CONEXAO_PERDIDA, "Operacao interrompida");
        }
    }

    private static ErroRequisicao comoErroRequisicao(Throwable causa) {
        Throwable atual = causa;
        while (atual instanceof CompletionException && atual.getCause() != null) {
            atual = atual.getCause();
        }
        if (atual instanceof ErroRequisicao erro) {
            return erro;
        }
        return new ErroRequisicao(CodigoErro.ERRO_INTERNO.name(), CodigoErro.ERRO_INTERNO.mensagem());
    }

    private void garantirConexao() throws ErroRequisicao {
        if (aberta()) {
            return;
        }
        try {
            conectar().get(opcoes.tempoLimiteConexaoMs() + 5_000L, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw comoErroRequisicao(e.getCause());
        } catch (TimeoutException e) {
            throw new ErroRequisicao(ErroRequisicao.TEMPO_ESGOTADO, "Tempo esgotado ao conectar");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErroRequisicao(ErroRequisicao.CONEXAO_PERDIDA, "Operacao interrompida");
        }
    }

    private ResultadoLogin loginSincrono(String login, String senha) throws ErroRequisicao {
        garantirConexao();
        ObjectNode resposta = aguardar(enviarLogin(login, senha));
        Perfil perfil;
        try {
            perfil = ProtocoloJson.lerPerfil(resposta.get("perfil"), "perfil");
        } catch (ErroDeProtocoloException e) {
            throw new ErroRequisicao(e.codigo().name(), e.codigo().mensagem(), e.campo());
        }
        synchronized (lock) {
            credenciais = new Credenciais(login, senha);
            tentativas = 0;
        }
        if (!aberta()) { // caiu entre a resposta e o registro das credenciais
            synchronized (lock) {
                credenciais = null;
            }
            throw new ErroRequisicao(ErroRequisicao.CONEXAO_PERDIDA, "Conexao perdida");
        }
        synchronized (lock) {
            definirEstado(EstadoConexao.AUTENTICADO, Map.of());
        }
        return new ResultadoLogin(texto(resposta, "sessao"), perfil);
    }

    private Void logoutSincrono() throws ErroRequisicao {
        ErroRequisicao falha = null;
        try {
            if (aberta()) {
                String id = novoId("out");
                try {
                    aguardar(requisitar(id, ProtocoloJson.montarLogout(id)));
                } catch (ErroRequisicao e) {
                    falha = e;
                }
            }
        } finally {
            fechar();
        }
        if (falha != null && !ErroRequisicao.CONEXAO_PERDIDA.equals(falha.codigo())) {
            throw falha;
        }
        return null;
    }

    private CompletableFuture<ObjectNode> enviarLogin(String login, String senha) {
        String id = novoId("log");
        ObjectNode campos = JsonNodeFactory.instance.objectNode();
        campos.put("login", login);
        campos.put("senha", senha);
        String dados = CifraManual.cifrar(chave, ProtocoloJson.serializar(campos));
        ObjectNode quadro = ProtocoloJson.montarLogin(
                id, dados, dispositivo, opcoes.tipoCliente(), opcoes.versaoCliente());
        return requisitar(id, quadro);
    }

    // ------------------------------------------------------------------
    // Requisicoes
    // ------------------------------------------------------------------

    private CompletableFuture<ObjectNode> requisitar(String id, ObjectNode quadro) {
        CompletableFuture<ObjectNode> futuro = new CompletableFuture<>();
        byte[] dados = ProtocoloJson.delimitar(quadro);
        Pendente pendente = new Pendente(
                futuro, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(opcoes.tempoLimiteRequisicaoMs()));
        ErroRequisicao erro = transmitir(dados, id, pendente);
        if (erro != null) {
            futuro.completeExceptionally(erro);
        }
        return futuro;
    }

    /** Envia {@code dados} pelo socket atual. Devolve o erro, ou {@code null} se enviou. */
    private ErroRequisicao transmitir(byte[] dados, String id, Pendente pendente) {
        Socket atual;
        lockEnvio.lock();
        try {
            atual = sock;
            if (atual == null) {
                return new ErroRequisicao(ErroRequisicao.SEM_CONEXAO, "Sem conexao com o servidor");
            }
            if (id != null && pendente != null) {
                synchronized (lock) {
                    pendentes.put(id, pendente);
                }
            }
            try {
                OutputStream saida = atual.getOutputStream();
                saida.write(dados);
                saida.flush();
                return null;
            } catch (IOException e) {
                if (id != null) {
                    synchronized (lock) {
                        pendentes.remove(id);
                    }
                }
            }
        } finally {
            lockEnvio.unlock();
        }
        // Falha no meio do envio pode deixar o fluxo corrompido: derruba a conexao.
        derrubar(atual);
        return new ErroRequisicao(ErroRequisicao.SEM_CONEXAO, "Falha ao enviar");
    }

    private boolean concluir(String id, ObjectNode valor, ErroRequisicao erro) {
        Pendente pendente;
        synchronized (lock) {
            pendente = pendentes.remove(id);
        }
        if (pendente == null) {
            return false;
        }
        if (erro == null) {
            pendente.futuro.complete(valor);
        } else {
            pendente.futuro.completeExceptionally(erro);
        }
        return true;
    }

    private void rejeitarPendentes(ErroRequisicao erro) {
        Map<String, Pendente> antigos;
        synchronized (lock) {
            antigos = pendentes;
            pendentes = new HashMap<>();
        }
        antigos.values().forEach(p -> p.futuro.completeExceptionally(erro));
    }

    private void expirar(long agoraNanos) {
        List<Pendente> vencidos = new ArrayList<>();
        synchronized (lock) {
            Iterator<Pendente> it = pendentes.values().iterator();
            while (it.hasNext()) {
                Pendente p = it.next();
                if (agoraNanos - p.prazoNanos >= 0) {
                    vencidos.add(p);
                    it.remove();
                }
            }
        }
        for (Pendente p : vencidos) {
            p.futuro.completeExceptionally(
                    new ErroRequisicao(ErroRequisicao.TEMPO_ESGOTADO, "O servidor nao respondeu a tempo"));
        }
    }

    // ------------------------------------------------------------------
    // Thread de rede
    // ------------------------------------------------------------------

    /** Chamar com {@code lock} tomado. */
    private void iniciarThread(CompletableFuture<Void> aberturaInicial, boolean automatico, boolean atrasarPrimeira) {
        Parada ciclo = new Parada();
        this.parar = ciclo;
        this.ativa = true;
        this.motivoFinal = null;
        Thread t = new Thread(
                () -> executar(ciclo, aberturaInicial, automatico, atrasarPrimeira), "comunicador-rede");
        t.setDaemon(true);
        t.start();
    }

    private void executar(Parada ciclo, CompletableFuture<Void> aberturaInicial, boolean automaticoInicial,
            boolean atrasarPrimeira) {
        CompletableFuture<Void> aberturaLocal = aberturaInicial;
        boolean automatico = automaticoInicial;
        try {
            if (atrasarPrimeira && !aguardarBackoff(ciclo)) {
                return;
            }
            while (!ciclo.acionada()) {
                Socket aberto = abrir();
                if (aberto != null) {
                    aberturaLocal = null;
                    if (!publicar(aberto, ciclo, automatico)) {
                        fecharSocket(aberto);
                        return;
                    }
                    try {
                        ler(aberto, ciclo);
                    } finally {
                        liberar(aberto);
                    }
                } else if (aberturaLocal != null) {
                    aberturaLocal.completeExceptionally(
                            new ErroRequisicao(ErroRequisicao.SEM_CONEXAO, "Nao foi possivel abrir a conexao"));
                    aberturaLocal = null;
                }
                if (ciclo.acionada()) {
                    return;
                }
                boolean temCredenciais;
                String motivo;
                synchronized (lock) {
                    temCredenciais = credenciais != null;
                    motivo = motivoFinal;
                }
                if (!temCredenciais) {
                    if (motivo == null) {
                        motivo = aberto != null ? "conexao_perdida" : "falha_ao_abrir";
                    }
                    finalizar(ciclo, motivo);
                    return;
                }
                automatico = true;
                if (!aguardarBackoff(ciclo)) {
                    return;
                }
            }
        } catch (Exception e) { // a thread de rede nunca morre em silencio
            erro(e, "rede");
            if (aberturaLocal != null) {
                aberturaLocal.completeExceptionally(
                        new ErroRequisicao(ErroRequisicao.SEM_CONEXAO, "Falha na conexao"));
            }
            finalizar(ciclo, "erro_interno");
        }
    }

    private Socket abrir() {
        try {
            return opcoes.fabricaSocket().abrir(
                    opcoes.host(), opcoes.porta(), (int) opcoes.tempoLimiteConexaoMs());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Torna {@code novo} o socket atual. Devolve {@code false} se {@code fechar()} ocorreu antes. */
    private boolean publicar(Socket novo, Parada ciclo, boolean automatico) {
        CompletableFuture<Void> aberturaConcluida;
        boolean relogin;
        lockEnvio.lock();
        try {
            synchronized (lock) {
                if (ciclo.acionada()) {
                    return false;
                }
                sock = novo;
                aberturaConcluida = abertura;
                abertura = null;
                relogin = automatico && credenciais != null;
                if (credenciais == null) {
                    tentativas = 0;
                }
                if (estado != EstadoConexao.RECONECTANDO) {
                    definirEstado(EstadoConexao.CONECTADO, Map.of());
                }
            }
        } finally {
            lockEnvio.unlock();
        }
        if (aberturaConcluida != null) {
            aberturaConcluida.complete(null);
        }
        if (relogin) {
            iniciarRelogin(novo);
        }
        return true;
    }

    private void liberar(Socket encerrado) {
        lockEnvio.lock();
        try {
            synchronized (lock) {
                if (sock == encerrado) {
                    sock = null;
                }
            }
        } finally {
            lockEnvio.unlock();
        }
        fecharSocket(encerrado);
        rejeitarPendentes(new ErroRequisicao(ErroRequisicao.CONEXAO_PERDIDA, "Conexao perdida"));
    }

    private void ler(Socket socket, Parada ciclo) {
        LeitorDeLinhas leitor = new LeitorDeLinhas();
        byte[] buffer = new byte[65536];
        long intervaloNanos = TimeUnit.MILLISECONDS.toNanos(opcoes.intervaloPingMs());
        long proximoPing = System.nanoTime() + intervaloNanos;
        InputStream entrada;
        try {
            socket.setSoTimeout(SONDA_MS);
            entrada = socket.getInputStream();
        } catch (IOException e) {
            return;
        }
        while (!ciclo.acionada()) {
            try {
                int lidos = entrada.read(buffer);
                if (lidos < 0) {
                    return;
                }
                for (LeitorDeLinhas.Linha linha : leitor.alimentar(buffer, 0, lidos)) {
                    tratarLinha(linha);
                }
            } catch (SocketTimeoutException e) {
                // sem dados neste periodo: reavalia ping e prazos
            } catch (IOException e) {
                return;
            }
            long agora = System.nanoTime();
            expirar(agora);
            if (agora - proximoPing >= 0) {
                proximoPing = agora + intervaloNanos;
                transmitir(ProtocoloJson.delimitar(ProtocoloJson.montarPing(novoId("p"))), null, null);
            }
            if (sock != socket) {
                return;
            }
        }
    }

    /** Espera o atraso da proxima tentativa. {@code false} se a thread deve encerrar. */
    private boolean aguardarBackoff(Parada ciclo) {
        int n;
        boolean esgotado;
        synchronized (lock) {
            n = tentativas;
            esgotado = !PoliticaReconexao.podeTentarNovamente(n);
            if (!esgotado) {
                tentativas = n + 1;
            }
        }
        if (esgotado) {
            finalizar(ciclo, "tentativas_esgotadas");
            return false;
        }
        double atrasoMs = opcoes.atrasoMs().applyAsDouble(n);
        synchronized (lock) {
            if (ciclo.acionada()) {
                return false;
            }
            Map<String, Object> detalhe = new LinkedHashMap<>();
            detalhe.put("tentativa", n + 1);
            detalhe.put("maximo", PoliticaReconexao.MAXIMO_TENTATIVAS);
            detalhe.put("atraso_ms", atrasoMs);
            definirEstado(EstadoConexao.RECONECTANDO, detalhe);
        }
        try {
            return !ciclo.aguardar((long) Math.max(0.0, Math.ceil(atrasoMs)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void finalizar(Parada ciclo, String motivo) {
        synchronized (lock) {
            if (parar != ciclo || ciclo.acionada()) {
                return;
            }
            ativa = false;
            definirEstado(EstadoConexao.DESCONECTADO, Map.of("motivo", motivo));
        }
    }

    // ------------------------------------------------------------------
    // Relogin automatico
    // ------------------------------------------------------------------

    private void iniciarRelogin(Socket socket) {
        Credenciais atuais;
        synchronized (lock) {
            atuais = credenciais;
        }
        if (atuais == null) {
            return;
        }
        enviarLogin(atuais.login(), atuais.senha())
                .whenComplete((resposta, erro) -> fimRelogin(socket, resposta, erro));
    }

    private void fimRelogin(Socket socket, ObjectNode resposta, Throwable falha) {
        if (falha == null) {
            Perfil perfil;
            try {
                perfil = ProtocoloJson.lerPerfil(resposta.get("perfil"), "perfil");
            } catch (ErroDeProtocoloException e) {
                erro(e, "relogin");
                derrubar(socket);
                return;
            }
            synchronized (lock) {
                if (sock != socket) {
                    return;
                }
                tentativas = 0;
                definirEstado(EstadoConexao.AUTENTICADO, Map.of());
                emitir(new EventoReautenticado(perfil));
            }
            return;
        }
        ErroRequisicao causa = comoErroRequisicao(falha);
        if (CodigoErro.CREDENCIAIS_INVALIDAS.name().equals(causa.codigo())) {
            // Senha ou conta mudou: reconectar nao adianta.
            synchronized (lock) {
                credenciais = null;
                motivoFinal = "credenciais_invalidas";
            }
            derrubar(socket);
            return;
        }
        if (ErroRequisicao.CONEXAO_PERDIDA.equals(causa.codigo())) {
            return; // a thread de rede ja trata a queda
        }
        // Outro erro (por exemplo SERVICO_INDISPONIVEL): derruba o socket e segue o backoff.
        erro(causa, "relogin");
        derrubar(socket);
    }

    // ------------------------------------------------------------------
    // Recepcao
    // ------------------------------------------------------------------

    private void tratarLinha(LeitorDeLinhas.Linha linha) {
        try {
            if (linha.muitoGrande()) {
                erro(new ErroDeProtocoloException(CodigoErro.MENSAGEM_MUITO_GRANDE), "quadro_invalido");
                return;
            }
            ObjectNode quadro;
            try {
                String texto = linha.texto();
                if (texto.isBlank()) {
                    return;
                }
                quadro = ProtocoloJson.interpretar(texto);
            } catch (ErroDeProtocoloException e) {
                erro(e, "quadro_invalido");
                return;
            }
            String op = quadro.get("op").textValue();
            switch (op) {
                case ProtocoloJson.OP_MESSAGE -> aoMensagem(quadro);
                case ProtocoloJson.OP_HISTORY -> aoHistorico(quadro);
                case ProtocoloJson.OP_ERRO -> erro(erroDe(quadro), "erro_geral");
                default -> aoResposta(quadro);
            }
        } catch (Exception e) {
            erro(e, "tratar_quadro");
        }
    }

    private void aoMensagem(ObjectNode quadro) throws ErroDeProtocoloException {
        MensagemChat bruta = ProtocoloJson.lerMensagem(quadro.get("mensagem"), "mensagem");
        emitir(new EventoMensagem(decifrarMensagem(bruta)));
    }

    private void aoResposta(ObjectNode quadro) {
        String id = idDe(quadro);
        if (id == null) {
            return;
        }
        if (ProtocoloJson.ehRespostaOk(quadro)) {
            concluir(id, quadro, null);
        } else {
            concluir(id, null, erroDe(quadro));
        }
    }

    private void aoHistorico(ObjectNode quadro) throws ErroDeProtocoloException {
        String id = idDe(quadro);
        if (!ProtocoloJson.ehRespostaOk(quadro)) {
            if (id != null) {
                concluir(id, null, erroDe(quadro));
            }
            return;
        }
        JsonNode brutas = quadro.get("mensagens");
        if (brutas == null || !brutas.isArray()) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO, "mensagens");
        }
        JsonNode lote = quadro.get("lote");
        boolean ultimo = quadro.path("ultimo").isBoolean() && quadro.get("ultimo").booleanValue();
        List<MensagemRecebida> mensagens = new ArrayList<>();
        for (JsonNode no : brutas) {
            try {
                mensagens.add(decifrarMensagem(ProtocoloJson.lerMensagem(no, "mensagens")));
            } catch (ErroDeProtocoloException e) {
                erro(e, "quadro_invalido"); // descarta so a mensagem malformada
            }
        }
        emitir(new EventoHistorico(lote != null && lote.canConvertToInt() ? lote.intValue() : 0, ultimo, mensagens));
        if (id == null) {
            return;
        }
        if (ultimo) {
            concluir(id, quadro, null);
        } else {
            synchronized (lock) { // ha mais lotes a caminho: renova o prazo
                Pendente pendente = pendentes.get(id);
                if (pendente != null) {
                    pendente.prazoNanos =
                            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(opcoes.tempoLimiteRequisicaoMs());
                }
            }
        }
    }

    private MensagemRecebida decifrarMensagem(MensagemChat bruta) {
        String conteudo = null;
        boolean ilegivel = false;
        try {
            conteudo = CifraManual.decifrar(chave, bruta.conteudo());
        } catch (DecifragemFalhouException e) {
            ilegivel = true;
            erro(new ErroRequisicao(CodigoErro.DECIFRAGEM_FALHOU.name(), "Mensagem recebida nao pode ser decifrada"),
                    "decifrar");
        }
        return new MensagemRecebida(bruta.mid(), bruta.remetente(), bruta.ts(), bruta.tipo(), conteudo, ilegivel);
    }

    private static ErroRequisicao erroDe(ObjectNode quadro) {
        String codigo = CodigoErro.ERRO_INTERNO.name();
        String mensagem = CodigoErro.ERRO_INTERNO.mensagem();
        String campo = null;
        JsonNode erro = quadro.get("erro");
        if (erro != null && erro.isObject()) {
            JsonNode c = erro.get("codigo");
            if (c != null && c.isTextual()) {
                String texto = c.textValue();
                codigo = texto.length() > 64 ? texto.substring(0, 64) : texto;
                mensagem = CodigoErro.deNome(codigo).map(CodigoErro::mensagem).orElse(mensagem);
            }
            JsonNode f = erro.get("campo");
            if (f != null && f.isTextual()) {
                String texto = f.textValue();
                campo = texto.length() > 64 ? texto.substring(0, 64) : texto;
            }
        }
        return new ErroRequisicao(codigo, mensagem, campo);
    }

    private static String idDe(ObjectNode quadro) {
        JsonNode id = quadro.get("id");
        return id != null && id.isTextual() ? id.textValue() : null;
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.get(campo);
        return valor != null && valor.isTextual() ? valor.textValue() : null;
    }

    // ------------------------------------------------------------------
    // Utilitarios
    // ------------------------------------------------------------------

    private static String novoId(String prefixo) {
        return prefixo + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Atualiza o estado e publica o evento. Chamar com {@code lock} tomado (ordem dos eventos). */
    private void definirEstado(EstadoConexao novo, Map<String, Object> detalhe) {
        this.estado = novo;
        emitir(new EventoEstado(novo, Map.copyOf(detalhe)));
    }

    /** Enfileira o evento para o ouvinte; nunca bloqueia. */
    private void emitir(EventoConexao evento) {
        if (ouvinte == null) {
            return;
        }
        despachante.execute(() -> {
            try {
                ouvinte.accept(evento);
            } catch (RuntimeException e) {
                // falha do ouvinte nunca derruba a conexao
            }
        });
    }

    private void erro(Exception erro, String contexto) {
        emitir(new EventoErro(erro, contexto));
    }

    /** Interrompe o fluxo; quem le o socket o fecha ao perceber. */
    private static void derrubar(Socket socket) {
        try {
            socket.shutdownInput();
        } catch (IOException | RuntimeException e) {
            // ja fechado
        }
        try {
            socket.shutdownOutput();
        } catch (IOException | RuntimeException e) {
            // ja fechado
        }
    }

    private static void fecharSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // nada a fazer
        }
    }
}
