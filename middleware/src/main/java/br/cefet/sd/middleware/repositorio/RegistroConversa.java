package br.cefet.sd.middleware.repositorio;

import br.cefet.sd.comum.protocolo.MensagemChat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Registro do Repositorio_Conversas: um objeto JSON por linha com {@code mid}, {@code remetente}
 * (login), {@code ts}, {@code tipo} e {@code conteudo} cifrado com a Chave_Repositorio.
 *
 * <p>{@code ts} e truncado para milissegundos na construcao, de modo que o registro e igual ao que
 * se le de volta do arquivo (ida e volta).
 *
 * @param mid Id_Mensagem (UUID v4)
 * @param remetente login do remetente
 * @param ts data/hora do servidor
 * @param tipo texto ou figurinha
 * @param conteudo Base64 da Cifra_Manual com a Chave_Repositorio
 */
public record RegistroConversa(
        String mid, String remetente, Instant ts, MensagemChat.Tipo tipo, String conteudo) {

    private static final DateTimeFormatter FORMATO_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public RegistroConversa {
        Objects.requireNonNull(mid, "mid");
        Objects.requireNonNull(remetente, "remetente");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(conteudo, "conteudo");
        ts = ts.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    /** {@code ts} no formato do arquivo: ISO-8601 UTC com milissegundos e {@code Z}. */
    public String tsTexto() {
        return FORMATO_TS.format(ts);
    }

    /** Interpreta um {@code ts} ISO-8601 UTC. */
    public static Instant lerTs(String texto) {
        return Instant.parse(texto);
    }
}
