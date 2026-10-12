package br.cefet.sd.cliente.desktop.ui;

import java.awt.CardLayout;
import javax.swing.JPanel;

/**
 * Raiz da interface: um {@code CardLayout} entre a {@link TelaLogin} e a {@link TelaSala}. A
 * {@code TelaSala} <b>nao existe</b> ate {@link #mostrarSala}, que so e chamado
 * depois do {@code ok:true} do login; ao sair, ela e removida de novo. Usar somente na EDT.
 */
public final class PainelPrincipal extends JPanel {
    private static final long serialVersionUID = 1L;

    public static final String CARTAO_LOGIN = "login";
    public static final String CARTAO_SALA = "sala";

    private final CardLayout cartoes = new CardLayout();
    private final TelaLogin telaLogin;
    private TelaSala telaSala;
    private String cartaoVisivel = CARTAO_LOGIN;

    public PainelPrincipal(TelaLogin telaLogin) {
        super();
        setLayout(cartoes);
        this.telaLogin = telaLogin;
        setName("painel-principal");
        add(telaLogin, CARTAO_LOGIN);
        cartoes.show(this, CARTAO_LOGIN);
    }

    /** Adiciona a sala ao {@code CardLayout} e a exibe. Substitui uma sala anterior, se houver. */
    public void mostrarSala(TelaSala sala) {
        if (telaSala != null) {
            remove(telaSala);
        }
        telaSala = sala;
        add(sala, CARTAO_SALA);
        cartoes.show(this, CARTAO_SALA);
        cartaoVisivel = CARTAO_SALA;
        revalidate();
        repaint();
    }

    /** Exibe o login e remove a sala. */
    public void mostrarLogin() {
        if (telaSala != null) {
            remove(telaSala);
            telaSala = null;
        }
        cartoes.show(this, CARTAO_LOGIN);
        cartaoVisivel = CARTAO_LOGIN;
        revalidate();
        repaint();
    }

    /** {@code true} se a tela da sala foi adicionada ao {@code CardLayout}. */
    public boolean temSala() {
        return telaSala != null;
    }

    public TelaSala sala() {
        return telaSala;
    }

    public TelaLogin telaLogin() {
        return telaLogin;
    }

    /** Nome do cartao visivel: {@link #CARTAO_LOGIN} ou {@link #CARTAO_SALA}. */
    public String cartaoVisivel() {
        return cartaoVisivel;
    }
}
