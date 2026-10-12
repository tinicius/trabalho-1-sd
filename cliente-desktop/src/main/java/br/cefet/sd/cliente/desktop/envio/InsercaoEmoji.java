package br.cefet.sd.cliente.desktop.envio;

/**
 * Insercao de emoji na posicao do cursor (Seletor_de_Emojis). Funcoes puras, sem Swing.
 *
 * <p>A posicao e um indice de unidade UTF-16 em {@code [0, texto.length()]}, exatamente o que
 * {@code JTextComponent.getCaretPosition()} devolve.
 */
public final class InsercaoEmoji {
    private InsercaoEmoji() {}

    /** Nova posicao do cursor e texto resultante de uma insercao. */
    public record Resultado(String texto, int posicao) {}

    /**
     * Devolve {@code antes + emoji + depois}, com {@code antes = texto[0, posicao)} e {@code depois
     * = texto[posicao, fim)}.
     *
     * @throws IllegalArgumentException se algum argumento for nulo ou a posicao estiver fora do
     *     texto
     */
    public static String inserir(String texto, int posicao, String emoji) {
        if (texto == null || emoji == null) {
            throw new IllegalArgumentException("Texto e emoji sao obrigatorios");
        }
        if (posicao < 0 || posicao > texto.length()) {
            throw new IllegalArgumentException("Posicao do cursor fora do texto");
        }
        return texto.substring(0, posicao) + emoji + texto.substring(posicao);
    }

    /** Como {@link #inserir}, mas devolve tambem a posicao do cursor logo apos o emoji. */
    public static Resultado inserirComCursor(String texto, int posicao, String emoji) {
        String novo = inserir(texto, posicao, emoji);
        return new Resultado(novo, posicao + emoji.length());
    }
}
