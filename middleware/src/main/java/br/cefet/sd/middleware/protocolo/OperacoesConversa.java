package br.cefet.sd.middleware.protocolo;

import br.cefet.sd.comum.cifra.Chave;
import br.cefet.sd.comum.cifra.CifraManual;
import br.cefet.sd.comum.cifra.DecifragemFalhouException;
import br.cefet.sd.comum.mascaramento.Mascaramento;
import br.cefet.sd.comum.protocolo.CodigoErro;
import br.cefet.sd.comum.protocolo.MensagemChat;
import br.cefet.sd.comum.protocolo.Perfil;
import br.cefet.sd.comum.protocolo.ProtocoloJson;
import br.cefet.sd.middleware.logs.EventoErro;
import br.cefet.sd.middleware.logs.LogEnvios;
import br.cefet.sd.middleware.logs.LogErros;
import br.cefet.sd.middleware.logs.LogErrosArquivo;
import br.cefet.sd.middleware.repositorio.RegistroConversa;
import br.cefet.sd.middleware.repositorio.RegistroJson;
import br.cefet.sd.middleware.repositorio.RepositorioConversas;
import br.cefet.sd.middleware.sessao.Distribuidor;
import br.cefet.sd.middleware.sessao.Sessao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Operacoes {@code send_message}, {@code history}, {@code logout} e {@code error_report} do
 * Protocolo.
 *
 * <p>Uso: {@code new OperacoesConversa(...).registrarEm(processador)}.
 *
 * <h2>send_message (exige Sessao; 30 por 10 s)</h2>
 *
 * <ol>
 *   <li>Valida {@code tipo} ({@code texto} ou {@code figurinha}) e a presenca de {@code conteudo}.
 *   <li>Decifra {@code conteudo} com a Chave_Transito, so em variavel local
 *       ({@code DECIFRAGEM_FALHOU}, com texto fixo no Log_Erros e sem gravar nada).
 *   <li>Valida o texto: nao vazio e ate 2000 pontos de codigo; figurinha no padrao
 *       {@code ^fig_[0-9]{2}$}. Falha: {@code CONTEUDO_INVALIDO}.
 *   <li>O remetente e sempre o da Sessao; qualquer {@code remetente} do payload e ignorado.
 *   <li>Sob o {@link ProcessadorProtocolo#ordemLock()}: atribui {@code mid} (UUID v4) e {@code ts}
 *       (UTC, relogio injetado), anexa ao Repositorio_Conversas (conteudo cifrado com a
 *       Chave_Repositorio), anexa ao Log_Envios somente {@code ts} e {@code login} e publica o
 *       evento {@code message}, com o conteudo cifrado com a Chave_Transito (nonce novo), a todas as
 *       Sessoes, inclusive a do remetente (eco). Se a gravacao no repositorio falha, nada e
 *       distribuido nem registrado no Log_Envios e a resposta e {@code ERRO_INTERNO}.
 *   <li>Fora do lock, responde {@code ok:true} com {@code mid} e {@code ts}.
 * </ol>
 *
 * Falha ao gravar no Log_Envios, depois de a Mensagem ja estar persistida, vai ao Log_Erros e nao
 * impede a distribuicao: nao distribuir uma Mensagem gravada deixaria repositorio e salas
 * divergentes.
 *
 * <h2>history (exige Sessao)</h2>
 *
 * Responde pelo {@link EnviadorHistorico} com o {@code id} da requisicao. {@code desde} e opcional
 * (ISO-8601); texto que nao e data/hora gera {@code CAMPO_INVALIDO}. O enviador limita o inicio a
 * Janela_7_Dias, reparte em lotes de 100 e marca {@code ultimo:true} so no ultimo.
 *
 * <h2>logout (exige Sessao)</h2>
 *
 * Responde {@code ok:true}, deixa de distribuir novos quadros a Sessao e a encerra depois de
 * esvaziar a fila de saida, com motivo {@code logout}. O registro de {@code desconexao} no
 * Log_Acessos e feito pelo observador armado em {@link OperacoesAutenticacao}, e nao aqui.
 *
 * <h2>error_report (nao exige Sessao; 10 por minuto)</h2>
 *
 * Aceita de 1 a 20 eventos e ate 64 KiB por requisicao ({@code LIMITE_EXCEDIDO} acima disso). Cada
 * evento precisa de {@code evid}, {@code ts} (ISO-8601), {@code modulo}, {@code tipo} e
 * {@code descricao} em texto; {@code pilha} e {@code descartados} sao opcionais. Evento invalido e
 * ignorado e nao e confirmado (o Cliente o mantem no Buffer_Offline_Erros e, pelo limite de
 * tentativas, acaba por descarta-lo). Eventos validos tem {@code descricao} e {@code pilha}
 * mascaradas e sao gravados no Log_Erros com origem {@code cliente}; {@code sessao} e
 * {@code dispositivo} vem da Sessao quando autenticada, e o {@code dispositivo}/{@code cliente} do
 * proprio evento (se validos) completam o que faltar. Um {@code evid} ja visto (cache dos ultimos
 * {@value #CAPACIDADE_CACHE_EVID}) nao e gravado de novo, mas e confirmado em {@code aceitos}.
 */
public final class OperacoesConversa {

    /** Tamanho do cache de {@code evid} recentes. */
    public static final int CAPACIDADE_CACHE_EVID = 10_000;

    /** Maximo de pontos de codigo de um texto. */
    public static final int TEXTO_MAXIMO = 2000;

    /** Tamanho maximo de uma requisicao {@code error_report} (JSON em UTF-8). */
    public static final int ERROR_REPORT_MAXIMO_BYTES = 64 * 1024;

    static final String MOTIVO_LOGOUT = "logout";

    private static final String MODULO_SEND = "OperacoesConversa.send_message";
    private static final String MODULO_ENVIOS = "OperacoesConversa.log_envios";

    private static final Pattern PADRAO_FIGURINHA = Pattern.compile("fig_[0-9]{2}");
    private static final Pattern PADRAO_EVID = Pattern.compile("[0-9A-Za-z._:-]{1,64}");
    private static final Pattern PADRAO_DISPOSITIVO = Pattern.compile("[0-9A-Za-z-]{1,64}");
    private static final int MAXIMO_MODULO_E_TIPO = 200;

    private final RepositorioConversas repositorio;
    private final LogEnvios logEnvios;
    private final LogErros logErros;
    private final Distribuidor distribuidor;
    private final EnviadorHistorico enviadorHistorico;
    private final Chave chaveTransito;
    private final Chave chaveRepositorio;

    /** Ultimos {@code evid} gravados, do mais antigo ao mais recente; guardado por si mesmo. */
    private final Map<String, Boolean> evidRecentes = new LinkedHashMap<>() {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > CAPACIDADE_CACHE_EVID;
        }
    };

    /**
     * @param repositorio Repositorio_Conversas (recebe o conteudo cifrado com a Chave_Repositorio)
     * @param logEnvios Log_Envios (recebe somente {@code ts} e {@code login})
     * @param logErros Log_Erros (recebe os eventos de {@code error_report})
     * @param distribuidor difunde o evento {@code message}
     * @param enviadorHistorico responde a {@code history}
     * @param chaveTransito Chave_Transito (decifra {@code conteudo} e cifra o que se distribui)
     * @param chaveRepositorio Chave_Repositorio (cifra o que se grava)
     */
    public OperacoesConversa(
            RepositorioConversas repositorio,
            LogEnvios logEnvios,
            LogErros logErros,
            Distribuidor distribuidor,
            EnviadorHistorico enviadorHistorico,
            Chave chaveTransito,
            Chave chaveRepositorio) {
        this.repositorio = Objects.requireNonNull(repositorio, "repositorio");
        this.logEnvios = Objects.requireNonNull(logEnvios, "logEnvios");
        this.logErros = Objects.requireNonNull(logErros, "logErros");
        this.distribuidor = Objects.requireNonNull(distribuidor, "distribuidor");
        this.enviadorHistorico = Objects.requireNonNull(enviadorHistorico, "enviadorHistorico");
        this.chaveTransito = Objects.requireNonNull(chaveTransito, "chaveTransito");
        this.chaveRepositorio = Objects.requireNonNull(chaveRepositorio, "chaveRepositorio");
    }

    /** Registra as quatro operacoes no processador, com os limites de requisicao do Protocolo. */
    public void registrarEm(ProcessadorProtocolo processador) {
        Objects.requireNonNull(processador, "processador");
        processador.registrar(
                ProtocoloJson.OP_SEND_MESSAGE, LimiteRequisicoes.SEND_MESSAGE, this::enviarMensagem);
        processador.registrar(ProtocoloJson.OP_HISTORY, this::historico);
        processador.registrar(ProtocoloJson.OP_LOGOUT, this::sair);
        processador.registrar(
                ProtocoloJson.OP_ERROR_REPORT, LimiteRequisicoes.ERROR_REPORT, this::relatarErros);
    }

    // ------------------------------------------------------------------
    // send_message
    // ------------------------------------------------------------------

    private void enviarMensagem(Requisicao req) throws Exception {
        Sessao sessao = req.sessao();
        Perfil remetente = sessao.perfil().orElse(null);
        if (remetente == null) {
            req.responderErro(CodigoErro.NAO_AUTENTICADO);
            return;
        }

        JsonNode noTipo = req.quadro().get("tipo");
        if (noTipo == null || noTipo.isNull()) {
            req.responderErro(CodigoErro.CAMPO_AUSENTE, "tipo");
            return;
        }
        MensagemChat.Tipo tipo = noTipo.isTextual() ? MensagemChat.Tipo.deValor(noTipo.textValue()) : null;
        if (tipo == null) {
            req.responderErro(CodigoErro.CAMPO_INVALIDO, "tipo");
            return;
        }
        JsonNode noConteudo = req.quadro().get("conteudo");
        if (noConteudo == null || noConteudo.isNull()
                || (noConteudo.isTextual() && noConteudo.textValue().isEmpty())) {
            req.responderErro(CodigoErro.CAMPO_AUSENTE, "conteudo");
            return;
        }
        if (!noConteudo.isTextual()) {
            req.responderErro(CodigoErro.FORMATO_INVALIDO, "conteudo");
            return;
        }

        // O texto legivel existe apenas nestas variaveis locais: nunca vai a log nem a excecao.
        String texto;
        try {
            texto = CifraManual.decifrar(chaveTransito, noConteudo.textValue());
        } catch (DecifragemFalhouException e) {
            // Texto fixo e sem causa: nada recebido vai ao Log_Erros.
            req.processador().registrarErro(
                    MODULO_SEND, new DecifragemFalhouException("Falha ao decifrar o campo conteudo"), sessao);
            req.responderErro(CodigoErro.DECIFRAGEM_FALHOU);
            return;
        }
        if (!conteudoValido(tipo, texto)) {
            req.responderErro(CodigoErro.CONTEUDO_INVALIDO);
            return;
        }

        // Cifras fora do lock: independem de mid e ts e encurtam a secao critica.
        String conteudoRepositorio = CifraManual.cifrar(chaveRepositorio, texto);
        String conteudoTransito = CifraManual.cifrar(chaveTransito, texto);

        ProcessadorProtocolo processador = req.processador();
        String mid;
        String ts;
        ReentrantLock ordem = processador.ordemLock();
        ordem.lock();
        try {
            Instant agora = processador.relogio().agora().truncatedTo(ChronoUnit.MILLIS);
            mid = UUID.randomUUID().toString();
            ts = ProcessadorProtocolo.formatarTs(agora);
            try {
                repositorio.anexar(new RegistroConversa(
                        mid, remetente.login(), agora, tipo, conteudoRepositorio));
            } catch (Exception e) {
                processador.registrarErro(MODULO_SEND, e, sessao);
                req.responderErro(CodigoErro.ERRO_INTERNO);
                return; // nao grava no Log_Envios nem distribui
            }
            try {
                logEnvios.registrar(agora, remetente.login());
            } catch (Exception e) {
                processador.registrarErro(MODULO_ENVIOS, e, sessao);
            }
            distribuidor.publicar(ProtocoloJson.serializar(ProtocoloJson.eventoMessage(
                    new MensagemChat(mid, remetente, ts, tipo, conteudoTransito))));
        } finally {
            ordem.unlock();
        }

        req.enviar(req.novaRespostaOk().put("mid", mid).put("ts", ts));
    }

    private static boolean conteudoValido(MensagemChat.Tipo tipo, String texto) {
        if (tipo == MensagemChat.Tipo.FIGURINHA) {
            return PADRAO_FIGURINHA.matcher(texto).matches();
        }
        return !texto.isEmpty() && texto.codePointCount(0, texto.length()) <= TEXTO_MAXIMO;
    }

    // ------------------------------------------------------------------
    // history
    // ------------------------------------------------------------------

    private void historico(Requisicao req) {
        Instant desde = null;
        JsonNode noDesde = req.quadro().get("desde");
        if (noDesde != null && !noDesde.isNull()) {
            if (!noDesde.isTextual()) {
                req.responderErro(CodigoErro.CAMPO_INVALIDO, "desde");
                return;
            }
            try {
                desde = Instant.parse(noDesde.textValue());
            } catch (DateTimeParseException e) {
                req.responderErro(CodigoErro.CAMPO_INVALIDO, "desde");
                return;
            }
        }
        enviadorHistorico.enviar(req.sessao(), req.id(), desde);
    }

    // ------------------------------------------------------------------
    // logout
    // ------------------------------------------------------------------

    private void sair(Requisicao req) {
        Sessao sessao = req.sessao();
        req.responderOk();
        distribuidor.remover(sessao);
        // Depois de entregar a resposta que ja esta na fila. A desconexao e gravada pelo observador.
        sessao.fecharAposEsvaziar(MOTIVO_LOGOUT);
    }

    // ------------------------------------------------------------------
    // error_report
    // ------------------------------------------------------------------

    private void relatarErros(Requisicao req) {
        JsonNode eventos = req.quadro().get("eventos");
        if (eventos == null || eventos.isNull()) {
            req.responderErro(CodigoErro.CAMPO_AUSENTE, "eventos");
            return;
        }
        if (!eventos.isArray()) {
            req.responderErro(CodigoErro.CAMPO_INVALIDO, "eventos");
            return;
        }
        if (eventos.isEmpty()) {
            req.responderErro(CodigoErro.CAMPO_INVALIDO, "eventos");
            return;
        }
        if (eventos.size() > ProtocoloJson.MAXIMO_EVENTOS_ERRO
                || ProtocoloJson.serializar(req.quadro()).getBytes(StandardCharsets.UTF_8).length
                        > ERROR_REPORT_MAXIMO_BYTES) {
            req.responderErro(CodigoErro.LIMITE_EXCEDIDO);
            return;
        }

        Sessao sessao = req.sessao();
        Instant agora = req.processador().relogio().agora();
        Set<String> aceitos = new LinkedHashSet<>();
        for (JsonNode evento : eventos) {
            EventoErro convertido = converter(evento, sessao, agora);
            if (convertido == null) {
                continue; // invalido: nao gravado e nao confirmado
            }
            if (primeiraVez(convertido.evid())) {
                try {
                    logErros.registrar(convertido);
                } catch (RuntimeException e) {
                    // Permite que o reenvio do Cliente seja gravado; o processador responde ERRO_INTERNO.
                    esquecer(convertido.evid());
                    throw e;
                }
            }
            aceitos.add(convertido.evid());
        }

        ObjectNode resposta = req.novaRespostaOk();
        ArrayNode lista = resposta.putArray("aceitos");
        aceitos.forEach(lista::add);
        req.enviar(resposta);
    }

    /**
     * Converte um evento do Cliente em {@link EventoErro}, com {@code descricao} e {@code pilha}
     * mascaradas e truncadas, ou devolve {@code null} se estiver fora do formato.
     */
    private static EventoErro converter(JsonNode evento, Sessao sessao, Instant agora) {
        if (evento == null || !evento.isObject()) {
            return null;
        }
        String evid = texto(evento, "evid");
        String modulo = texto(evento, "modulo");
        String tipo = texto(evento, "tipo");
        String descricao = texto(evento, "descricao");
        if (evid == null || !PADRAO_EVID.matcher(evid).matches()
                || modulo == null || modulo.isBlank()
                || tipo == null || tipo.isBlank()
                || descricao == null) {
            return null;
        }
        String pilha = "";
        JsonNode noPilha = evento.get("pilha");
        if (noPilha != null && !noPilha.isNull()) {
            if (!noPilha.isTextual()) {
                return null;
            }
            pilha = noPilha.textValue();
        }
        Instant ts;
        JsonNode noTs = evento.get("ts");
        if (noTs == null || !noTs.isTextual()) {
            return null;
        }
        try {
            ts = Instant.parse(noTs.textValue());
        } catch (DateTimeParseException e) {
            return null;
        }
        if (ts.isAfter(agora)) {
            ts = agora; // relogio do Cliente adiantado: nao deixa o registro fora da retencao
        }
        int descartados = 0;
        JsonNode noDescartados = evento.get("descartados");
        if (noDescartados != null && !noDescartados.isNull()) {
            if (!noDescartados.isIntegralNumber() || noDescartados.longValue() < 0) {
                return null;
            }
            descartados = (int) Math.min(noDescartados.longValue(), Integer.MAX_VALUE);
        }

        String dispositivo = sessao.dispositivo();
        if (dispositivo == null) {
            String doEvento = texto(evento, "dispositivo");
            dispositivo = doEvento != null && PADRAO_DISPOSITIVO.matcher(doEvento).matches() ? doEvento : null;
        }
        String cliente = sessao.cliente();
        if (cliente == null) {
            String doEvento = texto(evento, "cliente");
            cliente = doEvento != null && tipoClienteValido(doEvento) ? doEvento : null;
        }

        return new EventoErro(
                ts,
                EventoErro.Origem.CLIENTE,
                RegistroJson.truncar(modulo, MAXIMO_MODULO_E_TIPO),
                RegistroJson.truncar(tipo, MAXIMO_MODULO_E_TIPO),
                RegistroJson.truncar(Mascaramento.mascarar(descricao), LogErrosArquivo.MAXIMO_DESCRICAO),
                RegistroJson.truncar(Mascaramento.mascarar(pilha), LogErrosArquivo.MAXIMO_PILHA),
                sessao.id(),
                dispositivo,
                cliente,
                evid,
                descartados);
    }

    private static boolean tipoClienteValido(String tipo) {
        return "desktop".equals(tipo) || "web".equals(tipo) || "python".equals(tipo);
    }

    private static String texto(JsonNode objeto, String campo) {
        JsonNode no = objeto.get(campo);
        return no != null && no.isTextual() ? no.textValue() : null;
    }

    /** Marca o {@code evid} como visto; {@code true} se ainda nao estava no cache. */
    private boolean primeiraVez(String evid) {
        synchronized (evidRecentes) {
            return evidRecentes.put(evid, Boolean.TRUE) == null;
        }
    }

    private void esquecer(String evid) {
        synchronized (evidRecentes) {
            evidRecentes.remove(evid);
        }
    }
}
