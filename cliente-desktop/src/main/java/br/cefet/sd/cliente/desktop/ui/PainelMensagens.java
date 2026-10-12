package br.cefet.sd.cliente.desktop.ui;

import br.cefet.sd.cliente.desktop.rede.MensagemRecebida;
import br.cefet.sd.cliente.desktop.recursos.CatalogoRecursos;
import br.cefet.sd.cliente.desktop.sessao.FormatadorMensagem;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * Coluna de {@link BolhaMensagem} dentro do {@code JScrollPane} da sala. Mantem as mensagens
 * ordenadas por (ts, mid) e ignora um {@code mid} que ja esta na coluna. Usar somente na EDT.
 */
final class PainelMensagens extends JPanel implements Scrollable {

    private static final int ESPACAMENTO = 4;

    private final CatalogoRecursos catalogo;
    private final String loginProprio;
    private final ZoneId zona;
    private final List<MensagemRecebida> ordenadas = new ArrayList<>();
    private final java.util.Set<String> mids = new java.util.HashSet<>();

    PainelMensagens(CatalogoRecursos catalogo, String loginProprio, ZoneId zona) {
        super(new ColunaLayout());
        this.catalogo = catalogo;
        this.loginProprio = loginProprio;
        this.zona = zona;
        setBackground(Color.WHITE);
        setName("painel-mensagens");
    }

    /** Insere as mensagens novas na posicao certa; devolve quantas entraram. */
    int adicionar(Collection<MensagemRecebida> novas) {
        int entraram = 0;
        for (MensagemRecebida m : novas) {
            if (!mids.add(m.mid())) {
                continue;
            }
            int posicao = FormatadorMensagem.posicaoDeInsercao(ordenadas, m);
            ordenadas.add(posicao, m);
            boolean propria = m.remetente().login().equals(loginProprio);
            add(new BolhaMensagem(m, catalogo, propria, zona), posicao);
            entraram++;
        }
        if (entraram > 0) {
            revalidate();
            repaint();
        }
        return entraram;
    }

    // ----- Scrollable ------------------------------------------------------

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(480, 360);
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visivel, int orientacao, int direcao) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visivel, int orientacao, int direcao) {
        return orientacao == SwingConstants.VERTICAL ? Math.max(24, visivel.height - 24) : visivel.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    /** Empilha as bolhas com a largura do painel; a altura de cada uma depende dessa largura. */
    private static final class ColunaLayout implements LayoutManager {
        @Override
        public void addLayoutComponent(String nome, Component c) {}

        @Override
        public void removeLayoutComponent(Component c) {}

        @Override
        public Dimension preferredLayoutSize(Container pai) {
            Insets in = pai.getInsets();
            int largura = pai.getWidth() > 0 ? pai.getWidth() - in.left - in.right : 400;
            int altura = ESPACAMENTO;
            for (Component c : pai.getComponents()) {
                if (c instanceof BolhaMensagem b) {
                    altura += b.alturaPara(largura) + ESPACAMENTO;
                }
            }
            return new Dimension(largura + in.left + in.right, altura + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container pai) {
            return new Dimension(120, 40);
        }

        @Override
        public void layoutContainer(Container pai) {
            Insets in = pai.getInsets();
            int largura = pai.getWidth() - in.left - in.right;
            int y = in.top + ESPACAMENTO;
            for (Component c : pai.getComponents()) {
                if (c instanceof BolhaMensagem b) {
                    int h = b.alturaPara(largura);
                    b.setBounds(in.left, y, largura, h);
                    y += h + ESPACAMENTO;
                }
            }
        }
    }
}
