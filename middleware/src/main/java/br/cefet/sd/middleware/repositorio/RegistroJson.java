package br.cefet.sd.middleware.repositorio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Apoio comum a serializacao dos registros em arquivo (Repositorio_Conversas e logs): objeto JSON
 * compacto em uma unica linha, {@code ts} em ISO-8601 UTC com milissegundos e {@code Z}.
 *
 * <p>O JSON compacto escapa {@code \n} e {@code \r} dentro de strings, entao a linha serializada
 * nunca contem quebra de linha e cabe em uma chamada de {@link RepositorioArquivo#anexar}. As
 * mensagens das excecoes lancadas aqui nunca reproduzem o conteudo da linha.
 */
public final class RegistroJson {

    private static final DateTimeFormatter FORMATO_TS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final ObjectMapper MAPEADOR =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private RegistroJson() {}

    /** Objeto vazio, para montar o registro campo a campo (a ordem dos campos e preservada). */
    public static ObjectNode novo() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** Serializa o objeto como JSON compacto de uma linha, sem {@code \n}. */
    public static String serializar(ObjectNode objeto) {
        try {
            return MAPEADOR.writeValueAsString(objeto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar registro", e);
        }
    }

    /**
     * Interpreta uma linha como objeto JSON.
     *
     * @throws IllegalArgumentException se a linha nao e um unico objeto JSON valido
     */
    public static ObjectNode lerObjeto(String linha) {
        if (linha == null) {
            throw new IllegalArgumentException("Linha ausente");
        }
        JsonNode raiz;
        try {
            raiz = MAPEADOR.readTree(linha);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalArgumentException("Linha nao e JSON valido");
        }
        if (raiz == null || !raiz.isObject()) {
            throw new IllegalArgumentException("Linha nao e um objeto JSON");
        }
        return (ObjectNode) raiz;
    }

    /** {@code ts} no formato do arquivo (truncado para milissegundos). */
    public static String tsParaTexto(Instant ts) {
        return FORMATO_TS.format(ts.truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * Interpreta um {@code ts} ISO-8601 UTC.
     *
     * @throws IllegalArgumentException se o texto nao e um instante valido
     */
    public static Instant lerTs(String texto) {
        try {
            return Instant.parse(texto);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("ts invalido");
        }
    }

    /**
     * Valor textual do campo.
     *
     * @throws IllegalArgumentException se o campo falta, e {@code null} ou nao e texto
     */
    public static String textoObrigatorio(ObjectNode objeto, String campo) {
        JsonNode valor = objeto.get(campo);
        if (valor == null || !valor.isTextual()) {
            throw new IllegalArgumentException("Campo ausente ou invalido: " + campo);
        }
        return valor.textValue();
    }

    /**
     * Valor textual do campo, ou {@code null} se faltar ou for {@code null}.
     *
     * @throws IllegalArgumentException se o campo existe e nao e texto nem {@code null}
     */
    public static String textoOuNulo(ObjectNode objeto, String campo) {
        JsonNode valor = objeto.get(campo);
        if (valor == null || valor.isNull()) {
            return null;
        }
        if (!valor.isTextual()) {
            throw new IllegalArgumentException("Campo invalido: " + campo);
        }
        return valor.textValue();
    }

    /**
     * Valor inteiro nao negativo do campo.
     *
     * @throws IllegalArgumentException se o campo falta ou nao e inteiro nao negativo
     */
    public static int inteiroNaoNegativo(ObjectNode objeto, String campo) {
        JsonNode valor = objeto.get(campo);
        if (valor == null || !valor.isIntegralNumber() || !valor.canConvertToInt() || valor.intValue() < 0) {
            throw new IllegalArgumentException("Campo ausente ou invalido: " + campo);
        }
        return valor.intValue();
    }

    /**
     * Limita o texto a {@code maximo} caracteres sem separar um par substituto UTF-16. {@code null}
     * continua {@code null}.
     */
    public static String truncar(String texto, int maximo) {
        if (texto == null || texto.length() <= maximo) {
            return texto;
        }
        int fim = maximo;
        if (fim > 0 && Character.isHighSurrogate(texto.charAt(fim - 1))) {
            fim--;
        }
        return texto.substring(0, fim);
    }
}
