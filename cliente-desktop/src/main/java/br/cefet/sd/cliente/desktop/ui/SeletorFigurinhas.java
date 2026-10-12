package br.cefet.sd.cliente.desktop.ui;

import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JPopupMenu;

/**
 * Seletor de Figurinhas: grade de miniaturas clicaveis. Ao clicar, entrega o
 * identificador ({@code fig_NN}) ao {@code aoEscolher} e fecha; o envio e imediato.
 */
public final class SeletorFigurinhas extends JPopupMenu {
    private static final long serialVersionUID = 1L;

    public static final int COLUNAS = 4;
    public static final int LADO_MINIATURA = 64;

    private final List<JButton> botoes = new ArrayList<>();

    /**
     * @param figurinhas imagens por identificador, em ordem de exibicao
     * @param aoEscolher recebe o identificador escolhido (chamado na EDT)
     */
    public SeletorFigurinhas(Map<String, BufferedImage> figurinhas, Consumer<String> aoEscolher) {
        super("Figurinhas");
        int linhas = Math.max(1, (figurinhas.size() + COLUNAS - 1) / COLUNAS);
        setLayout(new GridLayout(linhas, COLUNAS, 4, 4));
        setName("seletor-figurinhas");
        for (Map.Entry<String, BufferedImage> entrada : figurinhas.entrySet()) {
            String id = entrada.getKey();
            JButton botao = new JButton(Imagens.icone(entrada.getValue(), LADO_MINIATURA, LADO_MINIATURA));
            botao.setName(id);
            botao.setToolTipText(id);
            botao.setMargin(new Insets(2, 2, 2, 2));
            botao.setFocusable(false);
            botao.getAccessibleContext().setAccessibleName("Figurinha " + id);
            botao.addActionListener(e -> {
                setVisible(false);
                aoEscolher.accept(id);
            });
            botoes.add(botao);
            add(botao);
        }
    }

    public List<JButton> botoes() {
        return List.copyOf(botoes);
    }
}
