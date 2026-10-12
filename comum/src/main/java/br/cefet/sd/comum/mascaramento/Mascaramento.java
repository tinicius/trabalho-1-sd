package br.cefet.sd.comum.mascaramento;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mascaramento de segredos em textos de eventos de erro.
 *
 * <p>Regras:
 * <ol>
 *   <li>O valor que segue {@code senha}, {@code password}, {@code pwd}, {@code chave},
 *       {@code key}, {@code token} ou {@code secret} nas formas {@code nome=valor},
 *       {@code nome: valor} e {@code "nome":"valor"} vira {@code ***};</li>
 *   <li>sequencias Base64 com 24 ou mais caracteres e hexadecimais com 32 ou mais
 *       viram {@code [REDIGIDO]}.</li>
 * </ol>
 *
 * <p>A funcao e idempotente e produz a mesma saida que as versoes JavaScript
 * ({@code mascaramento.js}) e Python ({@code mascaramento.py}). Por isso os
 * espacos em branco sao listados explicitamente (ASCII) e o fim de texto e
 * testado por lookahead, e nao por {@code $}.
 *
 * <p>Limite conhecido: texto de conversa arbitrario dentro de uma excecao de
 * biblioteca nao e reconhecido; a defesa e a convencao de que as excecoes do
 * projeto nunca incluem mensagens nem senhas.
 */
public final class Mascaramento {

    /** Substituto do valor de um segredo nomeado. */
    public static final String SEGREDO = "***";

    /** Substituto de sequencias Base64 e hexadecimais longas. */
    public static final String REDIGIDO = "[REDIGIDO]";

    // Espacos em branco ASCII: espaco, \t, \n, \r, \f e \u000B.
    private static final String ESPACOS = " \\t\\n\\r\\f\\u000B";

    private static final String NOMES = "senha|password|pwd|chave|key|token|secret";

    /**
     * Forma JSON: grupo 1 = nome; grupo 2 = {@code ":"} com espacos; grupo 3 = valor
     * (curto, ate uma aspa seguida de fim de texto ou delimitador).
     * Forma simples: grupo 4 = nome; grupo 5 = aspa opcional; grupo 6 = separador;
     * grupo 7 = valor (ate o proximo espaco em branco).
     */
    private static final Pattern SEGREDO_NOMEADO = Pattern.compile(
            "(?:(" + NOMES + ")(\"[ \\t]*:[ \\t]*\")([^\\n\\r]*?)\""
                    + "(?![^" + ESPACOS + ",}\\]])"
                    + "|(" + NOMES + ")([\"']?)([ \\t]*[=:][ \\t]*)([^" + ESPACOS + "]+))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HEXADECIMAL = Pattern.compile("[0-9A-Fa-f]{32,}");

    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]{24,}={0,2}");

    private Mascaramento() {
    }

    /**
     * Mascara segredos em {@code texto}.
     *
     * @param texto texto a mascarar; pode ser {@code null}
     * @return o texto mascarado, ou {@code null} se {@code texto} for {@code null}
     */
    public static String mascarar(String texto) {
        if (texto == null) {
            return null;
        }
        String resultado = SEGREDO_NOMEADO.matcher(texto).replaceAll(Mascaramento::substituirSegredo);
        resultado = HEXADECIMAL.matcher(resultado).replaceAll(Matcher.quoteReplacement(REDIGIDO));
        return BASE64.matcher(resultado).replaceAll(Matcher.quoteReplacement(REDIGIDO));
    }

    private static String substituirSegredo(java.util.regex.MatchResult m) {
        String saida;
        if (m.group(1) != null) {
            saida = m.group(1) + m.group(2) + SEGREDO + "\"";
        } else {
            saida = m.group(4) + m.group(5) + m.group(6) + SEGREDO;
        }
        return Matcher.quoteReplacement(saida);
    }
}
