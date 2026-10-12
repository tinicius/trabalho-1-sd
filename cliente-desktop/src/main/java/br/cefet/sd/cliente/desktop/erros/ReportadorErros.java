package br.cefet.sd.cliente.desktop.erros;

import br.cefet.sd.cliente.desktop.rede.ErroRequisicao;
import br.cefet.sd.comum.mascaramento.Mascaramento;
import br.cefet.sd.comum.protocolo.ProtocoloJson;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Ponto central de reporte de excecoes do Cliente_Desktop (ReportadorErros).
 *
 * <p>Comportamento identico ao de {@code cliente-web/js/erros.js} e {@code
 * cliente-python/comunicador/nucleo/erros.py}:
 *
 * <ul>
 *   <li>{@link #reportar} monta o evento, mascara segredos ({@link Mascaramento}) e <b>so depois</b>
 *       trunca ({@code tipo} 200, {@code descricao} 2000, {@code pilha} 8000), grava no {@link
 *       BufferOfflineErros} e, se ha conexao, envia {@code error_report}. Sem conexao ou com falha, o
 *       evento permanece no buffer;
 *   <li>flag de reentrancia {@code emReporte}, por thread, com {@code try/finally}: enquanto a thread
 *       esta reportando, enviando ou gravando, qualquer erro novo vai so para o console local e nao
 *       gera evento. Excecoes internas nunca saem do Reportador;
 *   <li>esvaziamento depois do login e a cada 60 s: lotes de ate 20 eventos (60 KiB); so os {@code
 *       evid} presentes em {@code aceitos} saem do buffer;
 *   <li>{@code SEM_CONEXAO} e {@code CONEXAO_PERDIDA} nao consomem tentativa do evento;
 *   <li>com {@link Opcoes#segundoPlano()} (padrao), o envio e o esvaziamento rodam em threads daemon,
 *       para que a thread de eventos do Swing nunca espere pela rede.
 * </ul>
 */
public final class ReportadorErros {

    /** Intervalo do reenvio do buffer, em ms. */
    public static final long INTERVALO_ESVAZIAMENTO_MS = 60_000L;

    /** Tempo maximo de espera pela confirmacao de um {@code error_report}, em ms. */
    public static final long TEMPO_LIMITE_ENVIO_MS = 20_000L;

    public static final int MAXIMO_TIPO = 200;
    public static final int MAXIMO_DESCRICAO = 2000;
    public static final int MAXIMO_PILHA = 8000;

    private static final DateTimeFormatter FORMATO_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Parametros do Reportador. Use {@link #padrao()} e os metodos {@code com...}. */
    public record Opcoes(
            Supplier<String> dispositivo,
            String cliente,
            Clock relogio,
            Supplier<String> gerarId,
            BiConsumer<String, Throwable> console,
            long intervaloMs,
            long tempoLimiteEnvioMs,
            boolean segundoPlano) {

        public static Opcoes padrao() {
            return new Opcoes(
                    () -> null,
                    "desktop",
                    Clock.systemUTC(),
                    () -> UUID.randomUUID().toString().replace("-", ""),
                    ReportadorErros::consolePadrao,
                    INTERVALO_ESVAZIAMENTO_MS,
                    TEMPO_LIMITE_ENVIO_MS,
                    true);
        }

        public Opcoes comDispositivo(Supplier<String> novo) {
            return new Opcoes(novo, cliente, relogio, gerarId, console, intervaloMs, tempoLimiteEnvioMs, segundoPlano);
        }

        public Opcoes comDispositivo(String id) {
            return comDispositivo(() -> id);
        }

        public Opcoes comRelogio(Clock novo) {
            return new Opcoes(dispositivo, cliente, novo, gerarId, console, intervaloMs, tempoLimiteEnvioMs, segundoPlano);
        }

        public Opcoes comGerarId(Supplier<String> novo) {
            return new Opcoes(dispositivo, cliente, relogio, novo, console, intervaloMs, tempoLimiteEnvioMs, segundoPlano);
        }

        public Opcoes comConsole(BiConsumer<String, Throwable> novo) {
            return new Opcoes(dispositivo, cliente, relogio, gerarId, novo, intervaloMs, tempoLimiteEnvioMs, segundoPlano);
        }

        public Opcoes comIntervaloMs(long ms) {
            return new Opcoes(dispositivo, cliente, relogio, gerarId, console, ms, tempoLimiteEnvioMs, segundoPlano);
        }

        public Opcoes comTempoLimiteEnvioMs(long ms) {
            return new Opcoes(dispositivo, cliente, relogio, gerarId, console, intervaloMs, ms, segundoPlano);
        }

        public Opcoes comSegundoPlano(boolean valor) {
            return new Opcoes(dispositivo, cliente, relogio, gerarId, console, intervaloMs, tempoLimiteEnvioMs, valor);
        }
    }

    private final BufferOfflineErros buffer;
    private final Opcoes opcoes;

    private volatile ConexaoErros conexao;

    private final ThreadLocal<int[]> profundidade = ThreadLocal.withInitial(() -> new int[1]);
    private final Object lockEnvio = new Object();
    private final Object lockFila = new Object();
    private final Deque<String> fila = new ArrayDeque<>();
    private boolean trabalhadorAtivo;
    private CountDownLatch pararPeriodico = new CountDownLatch(0);
    private Thread periodico;

    public ReportadorErros(BufferOfflineErros buffer, ConexaoErros conexao, Opcoes opcoes) {
        this.buffer = buffer;
        this.conexao = conexao;
        this.opcoes = opcoes;
    }

    public ReportadorErros(BufferOfflineErros buffer) {
        this(buffer, null, Opcoes.padrao());
    }

    // ------------------------------------------------------------------
    // Flag de reentrancia
    // ------------------------------------------------------------------

    /** {@code true} enquanto a thread atual esta reportando, enviando ou gravando. */
    public boolean emReporte() {
        return profundidade.get()[0] > 0;
    }

    private void entrar() {
        profundidade.get()[0]++;
    }

    private void sair() {
        int[] p = profundidade.get();
        if (p[0] > 0) {
            p[0]--;
        }
    }

    // ------------------------------------------------------------------
    // API publica
    // ------------------------------------------------------------------

    /** Define (ou troca) a conexao usada para enviar. */
    public void definirConexao(ConexaoErros nova) {
        this.conexao = nova;
    }

    /** Reporta uma excecao sem modulo explicito. */
    public Optional<EventoPendente> reportar(Throwable excecao) {
        return reportar(excecao, null);
    }

    /**
     * Reporta uma excecao. Se chamado durante um reporte ({@link #emReporte()}), o erro vai so para
     * o console e nenhum evento e criado. Nunca lanca excecao.
     *
     * @param modulo arquivo ou modulo de origem, quando conhecido (pode ser {@code null})
     * @return o evento criado, ou vazio
     */
    public Optional<EventoPendente> reportar(Throwable excecao, String modulo) {
        if (emReporte()) {
            registrarLocal("Erro durante o reporte de erros (nao reportado)", excecao);
            return Optional.empty();
        }
        entrar();
        try {
            EventoPendente evento = montarEvento(
                    excecao,
                    modulo,
                    FORMATO_TS.format(opcoes.relogio().instant()),
                    opcoes.gerarId().get(),
                    dispositivoAtual(),
                    opcoes.cliente());
            boolean retido = buffer.adicionar(evento).retido();
            if (retido && conectado()) {
                if (opcoes.segundoPlano()) {
                    agendar(evento.evid());
                } else {
                    enviarLote(Set.of(evento.evid()));
                }
            }
            return Optional.of(evento);
        } catch (Throwable e) {
            registrarLocal("Falha interna do ReportadorErros", e);
            return Optional.empty();
        } finally {
            sair();
        }
    }

    /**
     * Envia o que estiver retido, em lotes de ate 20 eventos, enquanto houver conexao e os lotes
     * forem integralmente confirmados. Devolve a quantidade de eventos confirmados.
     */
    public int esvaziar() {
        entrar();
        try {
            int confirmados = 0;
            int rodadas = (BufferOfflineErros.MAXIMO_EVENTOS_BUFFER + ProtocoloJson.MAXIMO_EVENTOS_ERRO - 1)
                            / ProtocoloJson.MAXIMO_EVENTOS_ERRO
                    + 1;
            for (int i = 0; i < rodadas; i++) {
                if (!conectado()) {
                    break;
                }
                int[] resultado = enviarLote(null);
                if (resultado == null) {
                    break;
                }
                confirmados += resultado[0];
                if (resultado[1] > 0) {
                    break;
                }
            }
            return confirmados;
        } catch (Throwable e) {
            registrarLocal("Falha ao esvaziar o Buffer_Offline_Erros", e);
            return 0;
        } finally {
            sair();
        }
    }

    /**
     * Chamar depois do login (e do relogin automatico): esvazia o buffer. Em segundo plano, volta
     * imediatamente.
     */
    public void aposLogin() {
        if (!opcoes.segundoPlano()) {
            esvaziar();
            return;
        }
        Thread t = new Thread(this::esvaziar, "comunicador-erros-login");
        t.setDaemon(true);
        t.start();
    }

    /** Inicia o esvaziamento periodico (a cada 60 s). Idempotente. */
    public synchronized void iniciar() {
        if (periodico != null && periodico.isAlive()) {
            return;
        }
        CountDownLatch parar = new CountDownLatch(1);
        this.pararPeriodico = parar;
        Thread t = new Thread(() -> {
            try {
                while (!parar.await(opcoes.intervaloMs(), TimeUnit.MILLISECONDS)) {
                    esvaziar();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "comunicador-erros-periodico");
        t.setDaemon(true);
        this.periodico = t;
        t.start();
    }

    /** Para o esvaziamento periodico. */
    public synchronized void parar() {
        pararPeriodico.countDown();
        periodico = null;
    }

    // ------------------------------------------------------------------
    // Montagem do evento (pura)
    // ------------------------------------------------------------------

    /**
     * Monta o evento do buffer. {@code descricao}, {@code pilha} e {@code tipo} sao mascarados e so
     * depois truncados (truncar antes poderia deixar parte de um segredo curto demais para ser
     * reconhecido).
     */
    public static EventoPendente montarEvento(
            Throwable excecao, String modulo, String ts, String evid, String dispositivo, String cliente) {
        String tipo = excecao == null ? "NullPointerException" : excecao.getClass().getName();
        String descricao = "";
        if (excecao != null) {
            try {
                String mensagem = excecao.getMessage();
                descricao = mensagem == null ? "" : mensagem;
            } catch (RuntimeException e) { // getMessage defeituoso
                descricao = excecao.getClass().getName();
            }
        }
        return new EventoPendente(
                evid,
                ts,
                moduloDe(excecao, modulo),
                truncar(Mascaramento.mascarar(tipo), MAXIMO_TIPO),
                truncar(Mascaramento.mascarar(descricao), MAXIMO_DESCRICAO),
                truncar(Mascaramento.mascarar(pilhaDe(excecao)), MAXIMO_PILHA),
                dispositivo,
                cliente,
                0);
    }

    /** Corta {@code texto} em {@code maximo} unidades UTF-16 sem deixar meio par substituto no fim. */
    public static String truncar(String texto, int maximo) {
        if (texto.length() <= maximo) {
            return texto;
        }
        int fim = maximo;
        if (Character.isHighSurrogate(texto.charAt(fim - 1))) {
            fim--;
        }
        return texto.substring(0, fim);
    }

    /** Nome do arquivo de um caminho (sem consulta nem fragmento), ou {@code null}. */
    static String nomeArquivo(String origem) {
        if (origem == null || origem.isEmpty()) {
            return null;
        }
        String limpo = origem.replace('\\', '/');
        for (char separador : new char[] {'?', '#'}) {
            int i = limpo.indexOf(separador);
            if (i >= 0) {
                limpo = limpo.substring(0, i);
            }
        }
        String nome = limpo.substring(limpo.lastIndexOf('/') + 1);
        return nome.isEmpty() ? null : nome;
    }

    /** Modulo de origem: o informado, senao o arquivo do quadro mais interno, senao {@code desconhecido}. */
    static String moduloDe(Throwable excecao, String informado) {
        String dado = nomeArquivo(informado);
        if (dado != null) {
            return dado;
        }
        if (excecao != null) {
            StackTraceElement[] pilha = excecao.getStackTrace();
            if (pilha.length > 0) {
                String nome = nomeArquivo(pilha[0].getFileName());
                if (nome != null) {
                    return nome;
                }
                return truncar(pilha[0].getClassName(), MAXIMO_TIPO);
            }
        }
        return "desconhecido";
    }

    private static String pilhaDe(Throwable excecao) {
        if (excecao == null) {
            return "";
        }
        try {
            StringWriter texto = new StringWriter();
            try (PrintWriter saida = new PrintWriter(texto)) {
                excecao.printStackTrace(saida);
            }
            return texto.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    /** Enfileira o envio de {@code evid} para uma unica thread de trabalho. */
    private void agendar(String evid) {
        synchronized (lockFila) {
            if (fila.size() >= BufferOfflineErros.MAXIMO_EVENTOS_BUFFER) {
                fila.pollFirst();
            }
            fila.addLast(evid);
            if (trabalhadorAtivo) {
                return;
            }
            trabalhadorAtivo = true;
        }
        try {
            Thread t = new Thread(this::drenar, "comunicador-erros-envio");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            synchronized (lockFila) {
                trabalhadorAtivo = false;
            }
            registrarLocal("Falha ao iniciar o envio de error_report", e);
        }
    }

    private void drenar() {
        try {
            while (true) {
                String evid;
                synchronized (lockFila) {
                    evid = fila.pollFirst();
                    if (evid == null) {
                        trabalhadorAtivo = false;
                        return;
                    }
                }
                entrar();
                try {
                    if (conectado()) {
                        enviarLote(Set.of(evid));
                    }
                } catch (Throwable e) {
                    registrarLocal("Falha ao enviar error_report", e);
                } finally {
                    sair();
                }
            }
        } finally {
            synchronized (lockFila) {
                trabalhadorAtivo = false;
            }
        }
    }

    /**
     * Envia um lote e registra o resultado no buffer. Os envios sao serializados para que o mesmo
     * evento nao saia em dois {@code error_report} ao mesmo tempo.
     *
     * @return {@code null} se nao havia lote; senao {@code {removidos, restantesNoLote}}
     */
    private int[] enviarLote(Set<String> apenas) {
        synchronized (lockEnvio) {
            ConexaoErros atual = conexao;
            if (atual == null || !atual.aberta()) {
                return null;
            }
            BufferOfflineErros.Lote lote = buffer.prepararLote(apenas);
            if (lote == null) {
                return null;
            }
            List<String> aceitos = List.of();
            boolean contarTentativa = true;
            try {
                List<String> resposta = atual.enviarErrorReport(lote.eventos())
                        .get(opcoes.tempoLimiteEnvioMs(), TimeUnit.MILLISECONDS);
                aceitos = resposta == null ? List.of() : resposta;
            } catch (ExecutionException e) {
                contarTentativa = !ehFalhaDeConexao(e.getCause());
                registrarLocal("Falha ao enviar error_report", e.getCause());
            } catch (TimeoutException e) {
                registrarLocal("Falha ao enviar error_report", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                contarTentativa = false;
                registrarLocal("Envio de error_report interrompido", e);
            } catch (RuntimeException e) {
                registrarLocal("Falha ao enviar error_report", e);
            }
            int removidos = buffer.resolverLote(lote, aceitos, contarTentativa).removidos();
            return new int[] {removidos, lote.evids().size() - removidos};
        }
    }

    /** Indica se {@code erro} representa falta de conexao (a tentativa nao e contada). */
    static boolean ehFalhaDeConexao(Throwable erro) {
        return erro instanceof ErroRequisicao e
                && (ErroRequisicao.SEM_CONEXAO.equals(e.codigo())
                        || ErroRequisicao.CONEXAO_PERDIDA.equals(e.codigo()));
    }

    private boolean conectado() {
        ConexaoErros atual = conexao;
        return atual != null && atual.aberta();
    }

    private String dispositivoAtual() {
        try {
            String valor = opcoes.dispositivo().get();
            return valor != null && !valor.isEmpty() ? valor : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void registrarLocal(String mensagem, Throwable erro) {
        try {
            opcoes.console().accept(mensagem, erro);
        } catch (Throwable ignorada) {
            // nem o console pode gerar novo evento
        }
    }

    private static void consolePadrao(String mensagem, Throwable erro) {
        System.err.println("[ReportadorErros] " + mensagem + ": "
                + (erro == null ? "null" : erro.getClass().getSimpleName() + ": " + erro.getMessage()));
    }

    // ------------------------------------------------------------------
    // Captura global
    // ------------------------------------------------------------------

    /**
     * Instala um {@link Thread.UncaughtExceptionHandler} padrao que reporta ao {@code reportador}
     * (cobre a thread de eventos do Swing e as threads daemon da conexao). O manipulador anterior e
     * preservado e chamado depois do reporte; sem anterior, imita o comportamento do Java.
     *
     * @return acao que desfaz a instalacao (so se o manipulador ainda for o instalado aqui)
     */
    public static Runnable instalarCapturaGlobal(ReportadorErros reportador) {
        Thread.UncaughtExceptionHandler anterior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler novo = (thread, erro) -> {
            try {
                reportador.reportar(erro);
            } catch (Throwable ignorada) {
                // nunca propaga
            }
            try {
                if (anterior != null) {
                    anterior.uncaughtException(thread, erro);
                } else {
                    System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                    erro.printStackTrace();
                }
            } catch (Throwable ignorada) {
                // nunca propaga
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(novo);
        return () -> {
            if (Thread.getDefaultUncaughtExceptionHandler() == novo) {
                Thread.setDefaultUncaughtExceptionHandler(anterior);
            }
        };
    }
}
