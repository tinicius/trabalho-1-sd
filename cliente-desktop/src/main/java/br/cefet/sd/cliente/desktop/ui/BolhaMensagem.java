package br.cefet.sd.cliente.desktop.ui;

import br.cefet.sd.cliente.desktop.rede.MensagemRecebida;
import br.cefet.sd.cliente.desktop.recursos.CatalogoRecursos;
import br.cefet.sd.cliente.desktop.sessao.FormatadorMensagem;
import br.cefet.sd.comum.protocolo.MensagemChat;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.time.ZoneId;
import java.util.Optional;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.border.LineBorder;

/**
 * Uma mensagem na sala: avatar 40x40, apelido na cor da conta, data/hora local e o
 * conteudo, que pode ser texto, a imagem da Figurinha, o marcador {@code [figurinha fig_99]} (id
 * desconhecido) ou "mensagem ilegivel" (nao decifrou).
 *
 * <p>O layout e manual para que a altura dependa da largura: {@link #alturaPara(int)} e usado pelo
 * {@code PainelMensagens}.
 */
final class BolhaMensagem extends JPanel {

    static final int LADO_AVATAR = 40;
    static final int LADO_FIGURINHA = 120;
    private static final int MARGEM = 8;
    private static final int ESPACO = 8;
    private static final Color FUNDO = new Color(0xF3F4F6);
    private static final Color FUNDO_PROPRIA = new Color(0xDCF8C6);

    private final MensagemRecebida mensagem;
    private final JLabel avatar = new JLabel();
    private final JLabel apelido = new JLabel();
    private final JLabel hora = new JLabel();
    private final Component conteudo;

    BolhaMensagem(MensagemRecebida mensagem, CatalogoRecursos catalogo, boolean propria, ZoneId zona) {
        super(null);
        this.mensagem = mensagem;
        setOpaque(true);
        setBackground(propria ? FUNDO_PROPRIA : FUNDO);
        setName("bolha-" + mensagem.mid());

        configurarAvatar(catalogo);

        apelido.setText(mensagem.remetente().apelido());
        apelido.setFont(apelido.getFont().deriveFont(Font.BOLD));
        apelido.setForeground(new Color(FormatadorMensagem.rgbDe(mensagem.remetente().cor())));
        apelido.setName("apelido");

        hora.setText(FormatadorMensagem.dataHora(mensagem.ts(), zona));
        hora.setFont(hora.getFont().deriveFont(Font.PLAIN, hora.getFont().getSize2D() - 2f));
        hora.setForeground(Color.GRAY);
        hora.setName("hora");

        conteudo = criarConteudo(catalogo);

        add(avatar);
        add(apelido);
        add(hora);
        add(conteudo);
    }

    private void configurarAvatar(CatalogoRecursos catalogo) {
        String id = mensagem.remetente().avatar();
        avatar.setName("avatar");
        Optional<BufferedImage> imagem = catalogo.avatar(id);
        if (imagem.isPresent()) {
            avatar.setIcon(Imagens.icone(imagem.get(), LADO_AVATAR, LADO_AVATAR));
        } else {
            avatar.setText(FormatadorMensagem.marcadorAvatar(id));
            avatar.setFont(avatar.getFont().deriveFont(Font.PLAIN, 9f));
            avatar.setHorizontalAlignment(SwingConstants.CENTER);
            avatar.setBorder(new LineBorder(Color.LIGHT_GRAY));
        }
        avatar.setToolTipText(mensagem.remetente().login());
    }

    private Component criarConteudo(CatalogoRecursos catalogo) {
        if (mensagem.ilegivel() || mensagem.conteudo() == null) {
            JTextArea t = texto(FormatadorMensagem.TEXTO_ILEGIVEL);
            t.setFont(t.getFont().deriveFont(Font.ITALIC));
            t.setForeground(Color.GRAY);
            return t;
        }
        if (mensagem.tipo() == MensagemChat.Tipo.FIGURINHA) {
            Optional<BufferedImage> imagem = catalogo.figurinha(mensagem.conteudo());
            if (imagem.isPresent()) {
                JLabel figurinha = new JLabel(Imagens.icone(imagem.get(), LADO_FIGURINHA, LADO_FIGURINHA));
                figurinha.setHorizontalAlignment(SwingConstants.LEADING);
                figurinha.setName("figurinha");
                figurinha.setToolTipText(mensagem.conteudo());
                return figurinha;
            }
            return texto(FormatadorMensagem.marcadorFigurinha(mensagem.conteudo()));
        }
        return texto(mensagem.conteudo());
    }

    private static JTextArea texto(String conteudo) {
        JTextArea t = new JTextArea(conteudo);
        t.setName("texto");
        t.setEditable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setOpaque(false);
        t.setBorder(null);
        return t;
    }

    private int larguraConteudo(int largura) {
        return Math.max(60, largura - (MARGEM + LADO_AVATAR + ESPACO) - MARGEM);
    }

    private int alturaCabecalho() {
        return Math.max(apelido.getPreferredSize().height, hora.getPreferredSize().height);
    }

    private int alturaConteudo(int larguraUtil) {
        if (conteudo instanceof JTextArea t) {
            t.setSize(larguraUtil, Short.MAX_VALUE);
            return t.getPreferredSize().height;
        }
        return conteudo.getPreferredSize().height;
    }

    /** Altura total da bolha quando ela tem {@code largura} pixels. */
    int alturaPara(int largura) {
        int h = MARGEM + alturaCabecalho() + 2 + alturaConteudo(larguraConteudo(largura)) + MARGEM;
        return Math.max(h, 2 * MARGEM + LADO_AVATAR);
    }

    @Override
    public void doLayout() {
        int largura = getWidth();
        int x = MARGEM + LADO_AVATAR + ESPACO;
        int utilW = larguraConteudo(largura);
        avatar.setBounds(MARGEM, MARGEM, LADO_AVATAR, LADO_AVATAR);
        int cab = alturaCabecalho();
        int apelidoW = Math.min(apelido.getPreferredSize().width, utilW);
        apelido.setBounds(x, MARGEM, apelidoW, cab);
        int horaW = hora.getPreferredSize().width;
        hora.setBounds(x + apelidoW + ESPACO, MARGEM, horaW, cab);
        int y = MARGEM + cab + 2;
        conteudo.setBounds(x, y, utilW, alturaConteudo(utilW));
    }

    @Override
    public Dimension getPreferredSize() {
        int largura = getWidth() > 0 ? getWidth() : 320;
        return new Dimension(largura, alturaPara(largura));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
