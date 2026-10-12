package br.cefet.sd.comum.protocolo;

import java.util.Objects;

/**
 * Objeto {@code mensagem} dos eventos {@code message} e {@code history}.
 *
 * <p>{@code conteudo} permanece cifrado com a Chave_de_Canal de Transito (Base64 da Cifra_Manual);
 * decifrar e responsabilidade de quem consome. {@code ts} e mantido como texto ISO-8601 UTC, exatamente
 * como o Middleware o gerou.
 *
 * @param mid identificador da mensagem (UUID v4), atribuido pelo Middleware
 * @param remetente perfil do remetente
 * @param ts data/hora do servidor, ISO-8601 UTC com milissegundos e {@code Z}
 * @param tipo texto ou figurinha
 * @param conteudo conteudo cifrado
 */
public record MensagemChat(String mid, Perfil remetente, String ts, Tipo tipo, String conteudo) {

    /** Tipo da mensagem. O valor de {@link #valor()} e o que vai em {@code tipo}. */
    public enum Tipo {
        TEXTO("texto"),
        FIGURINHA("figurinha");

        private final String valor;

        Tipo(String valor) {
            this.valor = valor;
        }

        public String valor() {
            return valor;
        }

        /** Converte o valor do protocolo ({@code texto} ou {@code figurinha}); {@code null} se invalido. */
        public static Tipo deValor(String valor) {
            for (Tipo t : values()) {
                if (t.valor.equals(valor)) {
                    return t;
                }
            }
            return null;
        }
    }

    public MensagemChat {
        Objects.requireNonNull(mid, "mid");
        Objects.requireNonNull(remetente, "remetente");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(conteudo, "conteudo");
    }
}
