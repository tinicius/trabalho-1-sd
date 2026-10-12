package br.cefet.sd.middleware.protocolo;

import br.cefet.sd.comum.protocolo.CodigoErro;
import br.cefet.sd.comum.protocolo.ErroDeProtocoloException;
import br.cefet.sd.comum.protocolo.LeitorDeLinhas;
import br.cefet.sd.comum.protocolo.ProtocoloJson;
import br.cefet.sd.middleware.logs.EventoErro;
import br.cefet.sd.middleware.logs.LogErros;
import br.cefet.sd.middleware.sessao.Sessao;
import br.cefet.sd.middleware.tempo.Relogio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Unico ponto de logica de protocolo do Middleware. Os
 * listeners TCP e WebSocket so repassam texto; este processador valida o envelope, aplica as regras
 * comuns e despacha a operacao ao {@link ManipuladorOperacao} registrado.
 *
 * <h2>O que esta classe garante</h2>
 *
 * <ul>
 *   <li>Envelope: JSON valido, objeto, {@code v} inteiro igual a 1, {@code op} texto, {@code id}
 *       ausente, nulo ou texto de 1 a 64 caracteres. Falhas: {@code FORMATO_INVALIDO},
 *       {@code VERSAO_NAO_SUPORTADA}.
 *   <li>Tamanho: acima de 1 MiB (UTF-8, sem o {@code \n} final) responde
 *       {@code MENSAGEM_MUITO_GRANDE}.
 *   <li>Operacao fora de {@link ProtocoloJson#OPERACOES_REQUISICAO}: {@code OPERACAO_DESCONHECIDA}.
 *   <li>Quando o envelope nao pode ser interpretado (ou a operacao e desconhecida), a resposta usa
 *       {@code op:"erro"} e {@code id:null}. Nos demais erros a resposta repete {@code op} e
 *       {@code id} da requisicao.
 *   <li>{@code send_message}, {@code history} e {@code logout} exigem Sessao autenticada; sem ela a
 *       resposta e {@code NAO_AUTENTICADO} e nenhum manipulador e chamado. A checagem
 *       vale mesmo que o manipulador ainda nao esteja registrado e antecede qualquer leitura do
 *       payload.
 *   <li>Limite de requisicoes por conexao e por operacao ({@link LimiteRequisicoes}), com
 *       {@code LIMITE_EXCEDIDO}. Conferido depois da autenticacao.
 *   <li>Excecao inesperada em qualquer etapa vira {@code ERRO_INTERNO} para o Cliente e um registro
 *       no Log_Erros; a conexao continua. Se o proprio Log_Erros falhar, o erro vai para
 *       {@code stderr}, sem recursao.
 *   <li>{@code ping} (nao exige Sessao) responde {@code {ok:true, ts}}.
 * </ul>
 *
 * <p>Em todos os casos acima a conexao permanece aberta; quem a encerra sao os manipuladores
 * (5 falhas de login, {@code logout}) ou a {@code Sessao} (fila cheia).
 *
 * <h2>Extensao</h2>
 *
 * As operacoes {@code register}, {@code login}, {@code send_message}, {@code history},
 * {@code logout} e {@code error_report} entram por {@link #registrar}. Uma operacao conhecida do
 * Protocolo sem manipulador registrado responde {@code OPERACAO_DESCONHECIDA} (mantendo
 * {@code op} e {@code id}).
 *
 * <h2>Concorrencia</h2>
 *
 * Uma conexao e atendida por uma thread por vez; conexoes diferentes chamam {@code processar}
 * concorrentemente. O {@link #ordemLock()} e compartilhado por todos os manipuladores que precisam
 * de ordem total (atribuir {@code mid}/{@code ts}, gravar e enfileirar a Mensagem).
 */
public final class ProcessadorProtocolo {

    /** Operacoes que exigem Sessao autenticada. */
    public static final Set<String> OPERACOES_COM_SESSAO = Set.of(
            ProtocoloJson.OP_SEND_MESSAGE, ProtocoloJson.OP_HISTORY, ProtocoloJson.OP_LOGOUT);

    private static final String MODULO = "ProcessadorProtocolo";

    private static final DateTimeFormatter FORMATO_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private record Registro(LimiteRequisicoes limite, ManipuladorOperacao manipulador) {}

    private final Relogio relogio;
    private final LogErros logErros;
    private final ReentrantLock ordemLock = new ReentrantLock();
    private final Map<String, Registro> manipuladores = new ConcurrentHashMap<>();
    private final Map<Sessao, EstadoConexao> estados = new ConcurrentHashMap<>();

    /**
     * @param relogio fonte de tempo injetada (UTC)
     * @param logErros destino dos erros internos (nunca lanca excecao)
     */
    public ProcessadorProtocolo(Relogio relogio, LogErros logErros) {
        this.relogio = Objects.requireNonNull(relogio, "relogio");
        this.logErros = Objects.requireNonNull(logErros, "logErros");
        registrar(ProtocoloJson.OP_PING, this::ping);
    }

    // ------------------------------------------------------------------
    // Extensao e acesso a dependencias comuns
    // ------------------------------------------------------------------

    /** Registra o manipulador de {@code op}, sem limite de requisicoes. */
    public void registrar(String op, ManipuladorOperacao manipulador) {
        registrar(op, null, manipulador);
    }

    /**
     * Registra o manipulador de {@code op}.
     *
     * @param op operacao de {@link ProtocoloJson#OPERACOES_REQUISICAO}
     * @param limite limite de requisicoes por conexao, ou {@code null} para nao limitar
     * @param manipulador logica da operacao
     * @throws IllegalArgumentException se {@code op} nao e uma operacao de requisicao do Protocolo
     * @throws IllegalStateException se {@code op} ja tem manipulador
     */
    public void registrar(String op, LimiteRequisicoes limite, ManipuladorOperacao manipulador) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(manipulador, "manipulador");
        if (!ProtocoloJson.OPERACOES_REQUISICAO.contains(op)) {
            throw new IllegalArgumentException("Operacao fora do Protocolo: " + op);
        }
        if (manipuladores.putIfAbsent(op, new Registro(limite, manipulador)) != null) {
            throw new IllegalStateException("Operacao ja registrada: " + op);
        }
    }

    /**
     * Trava que ordena a Mensagem: cobre a atribuicao de {@code mid} e {@code ts}, a gravacao no
     * Repositorio_Conversas e no Log_Envios e o enfileiramento no Distribuidor.
     */
    public ReentrantLock ordemLock() {
        return ordemLock;
    }

    public Relogio relogio() {
        return relogio;
    }

    /** {@code ts} no formato do Protocolo: ISO-8601 UTC com milissegundos e {@code Z}. */
    public static String formatarTs(Instant instante) {
        return FORMATO_TS.format(instante);
    }

    /**
     * Registra no Log_Erros uma excecao tratada ou observada em um manipulador, sem lancar excecao.
     * Se o proprio Log_Erros falhar, relata em {@code stderr} (so a classe, sem mensagem) e segue.
     *
     * @param modulo modulo de origem
     * @param sessao Sessao envolvida, ou {@code null}
     */
    public void registrarErro(String modulo, Throwable erro, Sessao sessao) {
        try {
            logErros.registrar(EventoErro.doMiddleware(
                    relogio.agora(), modulo, erro, sessao == null ? null : sessao.id()));
        } catch (Throwable falha) {
            System.err.println("Falha ao registrar no Log_Erros (" + falha.getClass().getName()
                    + "); erro original: " + erro.getClass().getName());
        }
    }

    // ------------------------------------------------------------------
    // Entradas dos listeners
    // ------------------------------------------------------------------

    /**
     * Processa um quadro de texto (linha TCP sem o {@code \n}, ou frame de texto WebSocket) e
     * responde pela propria Sessao. Nunca lanca excecao.
     */
    public void processar(Sessao sessao, String texto) {
        Objects.requireNonNull(sessao, "sessao");
        try {
            if (texto != null && excedeLimite(texto)) {
                erroGeral(sessao, CodigoErro.MENSAGEM_MUITO_GRANDE, null);
                return;
            }
            ObjectNode quadro;
            try {
                quadro = ProtocoloJson.interpretar(texto);
            } catch (ErroDeProtocoloException e) {
                erroGeral(sessao, e.codigo(), e.campo());
                return;
            }
            String op = quadro.get("op").textValue();
            if (!ProtocoloJson.OPERACOES_REQUISICAO.contains(op)) {
                erroGeral(sessao, CodigoErro.OPERACAO_DESCONHECIDA, "op");
                return;
            }
            JsonNode no = quadro.get("id");
            String id = no == null || no.isNull() ? null : no.textValue();
            if (id != null && (id.isEmpty() || id.length() > ProtocoloJson.TAMANHO_MAXIMO_ID)) {
                erroGeral(sessao, CodigoErro.FORMATO_INVALIDO, "id");
                return;
            }
            executar(new Requisicao(this, sessao, quadro, op, id, estadoDe(sessao)));
        } catch (Exception e) {
            // Falha inesperada antes de existir uma requisicao valida.
            registrarErro(MODULO, e, sessao);
            erroGeral(sessao, CodigoErro.ERRO_INTERNO, null);
        }
    }

    /**
     * Processa uma linha do {@link LeitorDeLinhas}: linha acima do limite responde
     * {@code MENSAGEM_MUITO_GRANDE}; bytes que nao sao UTF-8 valido respondem
     * {@code FORMATO_INVALIDO}; as demais seguem para {@link #processar(Sessao, String)}.
     */
    public void processarLinha(Sessao sessao, LeitorDeLinhas.Linha linha) {
        if (linha.muitoGrande()) {
            processarMuitoGrande(sessao);
            return;
        }
        String texto;
        try {
            texto = linha.texto();
        } catch (ErroDeProtocoloException e) {
            erroGeral(sessao, e.codigo(), e.campo());
            return;
        }
        processar(sessao, texto);
    }

    /** Responde {@code MENSAGEM_MUITO_GRANDE} a uma linha que passou de 1 MiB e foi descartada. */
    public void processarMuitoGrande(Sessao sessao) {
        erroGeral(sessao, CodigoErro.MENSAGEM_MUITO_GRANDE, null);
    }

    /** Responde {@code FORMATO_INVALIDO} a um frame binario do WebSocket. */
    public void processarBinario(Sessao sessao) {
        erroGeral(sessao, CodigoErro.FORMATO_INVALIDO, null);
    }

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    private void executar(Requisicao req) {
        try {
            if (OPERACOES_COM_SESSAO.contains(req.op()) && !req.sessao().autenticada()) {
                req.responderErro(CodigoErro.NAO_AUTENTICADO);
                return;
            }
            Registro registro = manipuladores.get(req.op());
            if (registro == null) {
                req.responderErro(CodigoErro.OPERACAO_DESCONHECIDA, "op");
                return;
            }
            LimiteRequisicoes limite = registro.limite();
            if (limite != null
                    && !req.estado().limitador(req.op(), limite).tentar(relogio.agora())) {
                req.responderErro(CodigoErro.LIMITE_EXCEDIDO);
                return;
            }
            registro.manipulador().tratar(req);
        } catch (Exception e) {
            registrarErro(MODULO + "." + req.op(), e, req.sessao());
            req.responderErro(CodigoErro.ERRO_INTERNO);
        }
    }

    private void ping(Requisicao req) {
        req.enviar(req.novaRespostaOk().put("ts", formatarTs(relogio.agora())));
    }

    /** Resposta {@code op:"erro"}, {@code id:null}, para quadro que nao pode ser interpretado. */
    private static void erroGeral(Sessao sessao, CodigoErro codigo, String campo) {
        sessao.enviar(ProtocoloJson.serializar(
                ProtocoloJson.respostaErro(ProtocoloJson.OP_ERRO, null, codigo, campo)));
    }

    private EstadoConexao estadoDe(Sessao sessao) {
        EstadoConexao atual = estados.get(sessao);
        if (atual != null) {
            return atual;
        }
        EstadoConexao novo = new EstadoConexao();
        EstadoConexao existente = estados.putIfAbsent(sessao, novo);
        if (existente != null) {
            return existente;
        }
        // Fora do computeIfAbsent: se a Sessao ja estiver fechada, o observador roda de imediato.
        sessao.aoFechar(motivo -> estados.remove(sessao));
        return novo;
    }

    /** {@code true} se o texto passa de 1 MiB em UTF-8, sem contar um {@code \n} final. */
    private static boolean excedeLimite(String texto) {
        int fim = texto.length();
        if (fim > 0 && texto.charAt(fim - 1) == '\n') {
            fim--;
        }
        int limite = LeitorDeLinhas.LIMITE_LINHA;
        if (fim > limite) {
            return true; // cada char ocupa ao menos 1 byte
        }
        if (fim <= limite / 3) {
            return false; // no maximo 3 bytes por char
        }
        long bytes = 0;
        for (int i = 0; i < fim; i++) {
            char c = texto.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < fim
                    && Character.isLowSurrogate(texto.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
            if (bytes > limite) {
                return true;
            }
        }
        return false;
    }
}
