package br.cefet.sd.cliente.desktop.ui;

import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JPopupMenu;

/**
 * Seletor_de_Emojis: {@code JPopupMenu} cujo layout e um {@link GridLayout} de
 * {@value #LINHAS} linhas x {@value #COLUNAS} colunas, com um botao por emoji ({@value #TOTAL}).
 * Ao clicar, entrega o emoji ao {@code aoEscolher} e fecha.
 */
public final class SeletorEmojis extends JPopupMenu {
    private static final long serialVersionUID = 1L;

    public static final int LINHAS = 6;
    public static final int COLUNAS = 8;
    public static final int TOTAL = LINHAS * COLUNAS;

    private final List<JButton> botoes = new ArrayList<>(TOTAL);

    /**
     * @param emojis exatamente {@value #TOTAL} emojis, na ordem de exibicao (linha por linha)
     * @param aoEscolher recebe o emoji escolhido (chamado na EDT)
     */
    public SeletorEmojis(List<String> emojis, Consumer<String> aoEscolher) {
        super("Emojis");
        if (emojis == null || emojis.size() != TOTAL) {
            throw new IllegalArgumentException("O Seletor_de_Emojis exige " + TOTAL + " emojis");
        }
        setLayout(new GridLayout(LINHAS, COLUNAS, 2, 2));
        setName("seletor-emojis");
        Font fonte = new Font(Font.SANS_SERIF, Font.PLAIN, 22);
        for (String emoji : emojis) {
            JButton botao = new JButton(emoji);
            botao.setFont(fonte);
            botao.setMargin(new Insets(2, 4, 2, 4));
            botao.setFocusable(false);
            botao.setToolTipText(emoji);
            botao.getAccessibleContext().setAccessibleName("Emoji " + emoji);
            botao.addActionListener(e -> {
                setVisible(false);
                aoEscolher.accept(emoji);
            });
            botoes.add(botao);
            add(botao);
        }
    }

    /** Os {@value #TOTAL} botoes, na ordem do {@code GridLayout}. */
    public List<JButton> botoes() {
        return List.copyOf(botoes);
    }
}
