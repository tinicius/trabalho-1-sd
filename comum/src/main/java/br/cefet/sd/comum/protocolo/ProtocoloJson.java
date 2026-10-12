package br.cefet.sd.comum.protocolo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Montagem e interpretacao dos quadros JSON do Protocolo (lado Java).
 *
 * <p>Acompanha {@code protocolo.py} (Cliente_Terceiro) e {@code protocolo.js} (Cliente_Web). Este
 * utilitario nao conhece chaves nem a Cifra_Manual: os campos cifrados ({@code dados} e
 * {@code conteudo}) chegam e saem como Base64 ja pronto. Campos desconhecidos sao ignorados.
 *
 * <p>Formato: um objeto JSON em UTF-8. No TCP, uma mensagem por linha terminada em {@code \n}
 * ({@link #delimitar}); no WebSocket, um frame de texto por mensagem ({@link #serializar}). O JSON
 * compacto escapa quebras de linha dentro de strings, entao o texto serializado nunca contem
 * {@code \n} literal.
 */
public final class ProtocoloJson {

    /** Versao atual do Protocolo. */
    public static final int VERSAO = 1;

    /** Tamanho maximo do campo {@code id} de correlacao. */
    public static final int TAMANHO_MAXIMO_ID = 64;

    /** Maximo de eventos por requisicao {@code error_report}. */
    public static final int MAXIMO_EVENTOS_ERRO = 20;

    // Operacoes de requisicao.
    public static final String OP_REGISTER = "register";
    public static final String OP_LOGIN = "login";
    public static final String OP_SEND_MESSAGE = "send_message";
    public static final String OP_HISTORY = "history";
    public static final String OP_ERROR_REPORT = "error_report";
    public static final String OP_PING = "ping";
    public static final String OP_LOGOUT = "logout";

    /** Evento enviado pelo Middleware sem requisicao. */
    public static final String OP_MESSAGE = "message";

    /** {@code op} da resposta a um quadro que nao pode ser interpretado. */
    public static final String OP_ERRO = "erro";

    /** Operacoes que um Cliente pode requisitar. */
    public static final Set<String> OPERACOES_REQUISICAO = Set.of(
            OP_REGISTER, OP_LOGIN, OP_SEND_MESSAGE, OP_HISTORY, OP_ERROR_REPORT, OP_PING, OP_LOGOUT);

    private static final JsonNodeFactory NOS = JsonNodeFactory.instance;

    /**
     * Mapeador compartilhado (thread-safe depois de configurado). Rejeita texto depois do primeiro
     * valor JSON; NaN e Infinity ja sao recusados por padrao.
     */
    private static final ObjectMapper MAPEADOR =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ProtocoloJson() {}

    // ------------------------------------------------------------------
    // Serializacao e delimitacao
    // ------------------------------------------------------------------

    /** Serializa um quadro como JSON compacto de uma linha, sem {@code \n} final. */
    public static String serializar(JsonNode quadro) {
        try {
            return MAPEADOR.writeValueAsString(quadro);
        } catch (JsonProcessingException e) {
            // Arvores JsonNode sempre sao serializaveis; nao deve ocorrer.
            throw new IllegalStateException("Falha ao serializar quadro", e);
        }
    }

    /** Serializa em UTF-8 e acrescenta o Delimitador {@code \n} (transporte TCP). */
    public static byte[] delimitar(JsonNode quadro) {
        byte[] corpo = serializar(quadro).getBytes(StandardCharsets.UTF_8);
        byte[] saida = new byte[corpo.length + 1];
        System.arraycopy(corpo, 0, saida, 0, corpo.length);
        saida[corpo.length] = '\n';
        return saida;
    }

    // ------------------------------------------------------------------
    // Requisicoes (Cliente -> Middleware)
    // ------------------------------------------------------------------

    /**
     * Monta o envelope {@code {v, op, id?}}. O {@code id} e omitido quando nulo.
     *
     * @throws IllegalArgumentException se {@code id} for vazio ou passar de 64 caracteres
     */
    public static ObjectNode requisicao(String op, String id) {
        ObjectNode quadro = NOS.objectNode();
        quadro.put("v", VERSAO);
        quadro.put("op", op);
        if (id != null) {
            if (id.isEmpty() || id.length() > TAMANHO_MAXIMO_ID) {
                throw new IllegalArgumentException("id deve ter de 1 a 64 caracteres");
            }
            quadro.put("id", id);
        }
        return quadro;
    }

    /** {@code register}: {@code dados} e a cifra (Transito) do JSON do cadastro. */
    public static ObjectNode montarRegister(String id, String dados) {
        return requisicao(OP_REGISTER, id).put("dados", dados);
    }

    /**
     * {@code login}: {@code dados} cifrado, UUID do {@code dispositivo} e {@code cliente}
     * ({@code tipo}: desktop, web ou python).
     */
    public static ObjectNode montarLogin(
            String id, String dados, String dispositivo, String tipoCliente, String versaoCliente) {
        ObjectNode quadro = requisicao(OP_LOGIN, id);
        quadro.put("dados", dados);
        quadro.put("dispositivo", dispositivo);
        ObjectNode cliente = quadro.putObject("cliente");
        cliente.put("tipo", tipoCliente);
        cliente.put("versao", versaoCliente);
        return quadro;
    }

    /** {@code send_message}: {@code conteudo} cifrado. O remetente nunca vai no quadro. */
    public static ObjectNode montarSendMessage(String id, MensagemChat.Tipo tipo, String conteudo) {
        return requisicao(OP_SEND_MESSAGE, id).put("tipo", tipo.valor()).put("conteudo", conteudo);
    }

    /** {@code history}: {@code desde} opcional (ISO-8601 UTC). */
    public static ObjectNode montarHistory(String id, String desde) {
        ObjectNode quadro = requisicao(OP_HISTORY, id);
        if (desde != null) {
            quadro.put("desde", desde);
        }
        return quadro;
    }

    /**
     * {@code error_report}: de 1 a 20 eventos de erro.
     *
     * @throws IllegalArgumentException se a lista estiver vazia ou tiver mais de 20 eventos
     */
    public static ObjectNode montarErrorReport(String id, List<? extends JsonNode> eventos) {
        if (eventos.isEmpty() || eventos.size() > MAXIMO_EVENTOS_ERRO) {
            throw new IllegalArgumentException("eventos deve ter de 1 a 20 itens");
        }
        ObjectNode quadro = requisicao(OP_ERROR_REPORT, id);
        ArrayNode lista = quadro.putArray("eventos");
        eventos.forEach(lista::add);
        return quadro;
    }

    public static ObjectNode montarPing(String id) {
        return requisicao(OP_PING, id);
    }

    public static ObjectNode montarLogout(String id) {
        return requisicao(OP_LOGOUT, id);
    }

    // ------------------------------------------------------------------
    // Respostas e eventos (Middleware -> Cliente)
    // ------------------------------------------------------------------

    /**
     * Resposta de sucesso: {@code {v, op, id, ok:true}}. O {@code id} sai {@code null} quando ausente.
     * O chamador acrescenta os campos da operacao ({@code sessao}, {@code perfil}, {@code mid}...).
     */
    public static ObjectNode respostaOk(String op, String id) {
        ObjectNode quadro = NOS.objectNode();
        quadro.put("v", VERSAO);
        quadro.put("op", op);
        quadro.put("id", id);
        quadro.put("ok", true);
        return quadro;
    }

    /**
     * Resposta de erro com {@code erro.codigo}, {@code erro.mensagem} (texto fixo do codigo) e
     * {@code erro.campo} (somente quando {@code campo} nao e nulo). Usa {@code op = "erro"} e
     * {@code id = null} quando o envelope nao pode ser interpretado.
     */
    public static ObjectNode respostaErro(String op, String id, CodigoErro codigo, String campo) {
        ObjectNode quadro = NOS.objectNode();
        quadro.put("v", VERSAO);
        quadro.put("op", op);
        quadro.put("id", id);
        quadro.put("ok", false);
        ObjectNode erro = quadro.putObject("erro");
        erro.put("codigo", codigo.name());
        erro.put("mensagem", codigo.mensagem());
        if (campo != null) {
            erro.put("campo", campo);
        }
        return quadro;
    }

    /** Evento {@code message} (sem {@code id} nem {@code ok}). */
    public static ObjectNode eventoMessage(MensagemChat mensagem) {
        ObjectNode quadro = NOS.objectNode();
        quadro.put("v", VERSAO);
        quadro.put("op", OP_MESSAGE);
        quadro.set("mensagem", paraJson(mensagem));
        return quadro;
    }

    /** Quadro {@code history} ({@code id} nulo no envio automatico apos o login). */
    public static ObjectNode eventoHistory(List<MensagemChat> mensagens, int lote, boolean ultimo, String id) {
        ObjectNode quadro = respostaOk(OP_HISTORY, id);
        quadro.put("lote", lote);
        quadro.put("ultimo", ultimo);
        ArrayNode lista = quadro.putArray("mensagens");
        mensagens.forEach(m -> lista.add(paraJson(m)));
        return quadro;
    }

    public static ObjectNode paraJson(Perfil perfil) {
        ObjectNode no = NOS.objectNode();
        no.put("login", perfil.login());
        no.put("apelido", perfil.apelido());
        no.put("avatar", perfil.avatar());
        no.put("cor", perfil.cor());
        return no;
    }

    public static ObjectNode paraJson(MensagemChat mensagem) {
        ObjectNode no = NOS.objectNode();
        no.put("mid", mensagem.mid());
        no.set("remetente", paraJson(mensagem.remetente()));
        no.put("ts", mensagem.ts());
        no.put("tipo", mensagem.tipo().valor());
        no.put("conteudo", mensagem.conteudo());
        return no;
    }

    // ------------------------------------------------------------------
    // Interpretacao
    // ------------------------------------------------------------------

    /**
     * Interpreta uma linha ou frame de texto como quadro do Protocolo. Tolera um {@code \n} e depois
     * um {@code \r} finais. Campos desconhecidos sao preservados e devem ser ignorados pelo consumidor.
     *
     * @return o objeto JSON validado
     * @throws ErroDeProtocoloException {@code FORMATO_INVALIDO} (JSON invalido, nao e objeto, {@code v}
     *     ou {@code op} ausentes ou com tipo errado, {@code id} que nao e texto) ou
     *     {@code VERSAO_NAO_SUPORTADA} ({@code v} inteiro diferente de 1)
     */
    public static ObjectNode interpretar(String texto) throws ErroDeProtocoloException {
        return interpretar(texto, true);
    }

    /**
     * Como {@link #interpretar(String)}; com {@code aceitarOperacaoDesconhecida} falso, tambem lanca
     * {@code OPERACAO_DESCONHECIDA} para {@code op} fora das operacoes conhecidas (requisicoes,
     * {@code message} e {@code erro}).
     */
    public static ObjectNode interpretar(String texto, boolean aceitarOperacaoDesconhecida)
            throws ErroDeProtocoloException {
        if (texto == null) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO);
        }
        String corpo = texto;
        if (corpo.endsWith("\n")) {
            corpo = corpo.substring(0, corpo.length() - 1);
        }
        if (corpo.endsWith("\r")) {
            corpo = corpo.substring(0, corpo.length() - 1);
        }

        JsonNode raiz;
        try {
            raiz = MAPEADOR.readTree(corpo);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO);
        }
        if (raiz == null || !raiz.isObject()) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO);
        }

        JsonNode v = raiz.get("v");
        JsonNode op = raiz.get("op");
        if (v == null || !v.isIntegralNumber()) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO, "v");
        }
        if (op == null || !op.isTextual()) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO, "op");
        }
        JsonNode id = raiz.get("id");
        if (id != null && !id.isNull() && !id.isTextual()) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO, "id");
        }
        if (!(v.canConvertToInt() && v.intValue() == VERSAO)) {
            throw new ErroDeProtocoloException(CodigoErro.VERSAO_NAO_SUPORTADA, "v");
        }
        if (!aceitarOperacaoDesconhecida && !operacaoConhecida(op.textValue())) {
            throw new ErroDeProtocoloException(CodigoErro.OPERACAO_DESCONHECIDA, "op");
        }
        return (ObjectNode) raiz;
    }

    /**
     * Interpreta os bytes de uma linha (sem o {@code \n}). Bytes que nao formam UTF-8 valido geram
     * {@code FORMATO_INVALIDO}.
     */
    public static ObjectNode interpretar(byte[] linha) throws ErroDeProtocoloException {
        return interpretar(decodificarUtf8(linha));
    }

    /** Decodifica UTF-8 de forma estrita; bytes invalidos geram {@code FORMATO_INVALIDO}. */
    public static String decodificarUtf8(byte[] bytes) throws ErroDeProtocoloException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO);
        }
    }

    private static boolean operacaoConhecida(String op) {
        return OPERACOES_REQUISICAO.contains(op) || OP_MESSAGE.equals(op) || OP_ERRO.equals(op);
    }

    /** Verdadeiro se o quadro e uma resposta com {@code ok} igual a {@code true}. */
    public static boolean ehRespostaOk(JsonNode quadro) {
        JsonNode ok = quadro.get("ok");
        return ok != null && ok.isBoolean() && ok.booleanValue();
    }

    /** Devolve {@code erro.codigo} de uma resposta {@code ok:false}, ou {@code null}. */
    public static String codigoDeErro(JsonNode quadro) {
        JsonNode ok = quadro.get("ok");
        JsonNode erro = quadro.get("erro");
        if (ok != null && ok.isBoolean() && !ok.booleanValue() && erro != null && erro.isObject()) {
            JsonNode codigo = erro.get("codigo");
            if (codigo != null && codigo.isTextual()) {
                return codigo.textValue();
            }
        }
        return null;
    }

    /**
     * Le o objeto {@code perfil} ou {@code remetente}; todos os quatro campos devem ser texto.
     *
     * @param campo nome usado em {@code ErroDeProtocoloException.campo()} (por exemplo
     *     {@code "perfil"})
     */
    public static Perfil lerPerfil(JsonNode no, String campo) throws ErroDeProtocoloException {
        if (no == null || !no.isObject()) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO, campo);
        }
        return new Perfil(
                textoObrigatorio(no, "login", campo),
                textoObrigatorio(no, "apelido", campo),
                textoObrigatorio(no, "avatar", campo),
                textoObrigatorio(no, "cor", campo));
    }

    /**
     * Le o objeto {@code mensagem} de {@code message} e {@code history}. O {@code conteudo} continua
     * cifrado.
     */
    public static MensagemChat lerMensagem(JsonNode no, String campo) throws ErroDeProtocoloException {
        if (no == null || !no.isObject()) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO, campo);
        }
        String mid = textoObrigatorio(no, "mid", campo);
        String ts = textoObrigatorio(no, "ts", campo);
        String conteudo = textoObrigatorio(no, "conteudo", campo);
        String valorTipo = textoObrigatorio(no, "tipo", campo);
        MensagemChat.Tipo tipo = MensagemChat.Tipo.deValor(valorTipo);
        if (tipo == null) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO, campo + ".tipo");
        }
        Perfil remetente = lerPerfil(no.get("remetente"), campo + ".remetente");
        return new MensagemChat(mid, remetente, ts, tipo, conteudo);
    }

    private static String textoObrigatorio(JsonNode objeto, String nome, String prefixo)
            throws ErroDeProtocoloException {
        JsonNode valor = objeto.get(nome);
        if (valor == null || !valor.isTextual()) {
            throw new ErroDeProtocoloException(CodigoErro.FORMATO_INVALIDO, prefixo + "." + nome);
        }
        return valor.textValue();
    }
}
