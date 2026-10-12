package br.cefet.sd.comum.emoticons;

import br.cefet.sd.comum.emoticons.TabelaConversao.Par;

/**
 * Converte emoticons em emojis conforme a Tabela_de_Conversao.
 *
 * <p>Algoritmo: percorre o texto da esquerda para a direita; em cada posicao tenta os emoticons do
 * mais longo ao mais curto. Ha correspondencia quando o texto inicia com o emoticon, o caractere
 * anterior e o inicio do texto ou um espaco em branco, e o caractere seguinte ao emoticon e o fim
 * do texto, um espaco em branco ou um de {@code . , ; ! ?}. Se corresponde, emite o emoji e avanca
 * o tamanho do emoticon; senao emite o caractere e avanca um.
 *
 * <p>"Espaco em branco" e a propriedade Unicode White_Space, enumerada explicitamente em
 * {@link #ehEspaco(char)} para que Java, JavaScript e Python concordem.
 */
public final class ConversorEmoticons {

    private ConversorEmoticons() {}

    public static String converter(String texto, TabelaConversao tabela) {
        if (texto == null || tabela == null) {
            throw new IllegalArgumentException("Texto e tabela sao obrigatorios");
        }
        StringBuilder saida = new StringBuilder(texto.length());
        int n = texto.length();
        int i = 0;
        while (i < n) {
            boolean inicioOk = i == 0 || ehEspaco(texto.charAt(i - 1));
            Par achado = null;
            if (inicioOk) {
                for (Par p : tabela.pares()) {
                    String emoticon = p.emoticon();
                    if (texto.startsWith(emoticon, i) && fronteiraDepois(texto, i + emoticon.length())) {
                        achado = p;
                        break;
                    }
                }
            }
            if (achado != null) {
                saida.append(achado.emoji());
                i += achado.emoticon().length();
            } else {
                saida.append(texto.charAt(i));
                i++;
            }
        }
        return saida.toString();
    }

    private static boolean fronteiraDepois(String texto, int fim) {
        if (fim >= texto.length()) {
            return true;
        }
        char c = texto.charAt(fim);
        return ehEspaco(c) || c == '.' || c == ',' || c == ';' || c == '!' || c == '?';
    }

    /** Unicode White_Space: U+0009-000D, 0020, 0085, 00A0, 1680, 2000-200A, 2028, 2029, 202F, 205F, 3000. */
    static boolean ehEspaco(char c) {
        return (c >= 0x0009 && c <= 0x000D)
                || c == 0x0020
                || c == 0x0085
                || c == 0x00A0
                || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028
                || c == 0x2029
                || c == 0x202F
                || c == 0x205F
                || c == 0x3000;
    }
}
