package br.cefet.sd.cliente.desktop.ui;

import br.cefet.sd.cliente.desktop.envio.CampoDeTexto;
import br.cefet.sd.cliente.desktop.envio.ControladorEnvio;
import br.cefet.sd.cliente.desktop.envio.ControladorEnvio.ResultadoEnvio;
import br.cefet.sd.cliente.desktop.rede.EstadoConexao;
import br.cefet.sd.cliente.desktop.rede.MensagemRecebida;
import br.cefet.sd.cliente.desktop.recursos.CatalogoRecursos;
import br.cefet.sd.cliente.desktop.sessao.AcoesSala;
import br.cefet.sd.cliente.desktop.sessao.MensagensUsuario;
import br.cefet.sd.comum.protocolo.Perfil;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Tela da sala de bate-papo. So e criada depois do login
 * confirmado. Usar somente na EDT.
 *
 * <p>O botao Enviar e a tecla Enter chamam o mesmo metodo ({@link #enviar()}), que delega ao {@link
 * ControladorEnvio}: texto vazio ou so de espacos nao envia nada nem altera o campo.
 */
public final class TelaSala extends JPanel {
    private static final long serialVersionUID = 1L;

    private final Perfil perfil;
    private final CatalogoRecursos catalogo;
    private final AcoesSala acoes;
    private final PainelMensagens painelMensagens;
    private final JScrollPane rolagem;
    private final JTextField campoTexto = new JTextField();
    private final JButton botaoEnviar = new JButton("Enviar");
    private final JButton botaoEmoji = new JButton("\uD83D\uDE00");
    private final JButton botaoFigurinha = new JButton("Figurinhas");
    private final JButton botaoSair = new JButton("Sair");
    private final JButton botaoNovaTentativa = new JButton("Tentar novamente");
    private final JLabel rotuloEstado = new JLabel("Conectado");
    private final JLabel rotuloAviso = new JLabel(" ");
    private final SeletorEmojis seletorEmojis;
    private final SeletorFigurinhas seletorFigurinhas;
    private final ControladorEnvio envio;
    private boolean autenticado = true;

    public TelaSala(Perfil perfil, CatalogoRecursos catalogo, AcoesSala acoes) {
        this(perfil, catalogo, acoes, ZoneId.systemDefault());
    }

    public TelaSala(Perfil perfil, CatalogoRecursos catalogo, AcoesSala acoes, ZoneId zona) {
        super(new BorderLayout(0, 4));
        this.perfil = perfil;
        this.catalogo = catalogo;
        this.acoes = acoes;
        setName("tela-sala");
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        painelMensagens = new PainelMensagens(catalogo, perfil.login(), zona);
        rolagem = new JScrollPane(painelMensagens);
        rolagem.setName("rolagem-mensagens");
        rolagem.setBorder(BorderFactory.createLineBorder(new Color(0xD0D0D0)));
        rolagem.getVerticalScrollBar().setUnitIncrement(24);

        seletorEmojis = new SeletorEmojis(catalogo.emojis(), this::inserirEmoji);
        seletorFigurinhas = new SeletorFigurinhas(catalogo.figurinhas(), this::enviarFigurinha);
        envio = new ControladorEnvio(
                catalogo.emoticons(), acoes.enviador(), new CampoSwing(campoTexto), SwingUtilities::invokeLater);

        add(criarTopo(), BorderLayout.NORTH);
        add(rolagem, BorderLayout.CENTER);
        add(criarRodape(), BorderLayout.SOUTH);
        mostrarEstado(EstadoConexao.AUTENTICADO, Map.of());
    }

    // ------------------------------------------------------------------
    // Montagem
    // ------------------------------------------------------------------

    private JPanel criarTopo() {
        JPanel topo = new JPanel(new BorderLayout(8, 0));
        JLabel quem = new JLabel(perfil.apelido() + " (" + perfil.login() + ")");
        quem.setFont(quem.getFont().deriveFont(Font.BOLD));
        quem.setForeground(new Color(br.cefet.sd.cliente.desktop.sessao.FormatadorMensagem.rgbDe(perfil.cor())));
        quem.setName("rotulo-perfil");
        catalogo.avatar(perfil.avatar())
                .ifPresent(img -> quem.setIcon(Imagens.icone(img, 28, 28)));
        quem.setIconTextGap(8);

        rotuloEstado.setName("rotulo-estado");
        rotuloEstado.setHorizontalAlignment(JLabel.CENTER);

        botaoNovaTentativa.setName("botao-nova-tentativa");
        botaoNovaTentativa.setVisible(false);
        botaoNovaTentativa.addActionListener(e -> acoes.tentarNovamente());

        botaoSair.setName("botao-sair");
        botaoSair.addActionListener(e -> acoes.sair());

        JPanel direita = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        direita.add(botaoNovaTentativa);
        direita.add(botaoSair);

        topo.add(quem, BorderLayout.WEST);
        topo.add(rotuloEstado, BorderLayout.CENTER);
        topo.add(direita, BorderLayout.EAST);
        return topo;
    }

    private JPanel criarRodape() {
        JPanel rodape = new JPanel();
        rodape.setLayout(new BoxLayout(rodape, BoxLayout.Y_AXIS));

        rotuloAviso.setName("rotulo-aviso");
        rotuloAviso.setForeground(new Color(0xB00020));
        rotuloAviso.setAlignmentX(LEFT_ALIGNMENT);

        botaoEmoji.setName("botao-emoji");
        botaoEmoji.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        botaoEmoji.setToolTipText("Emojis");
        botaoEmoji.getAccessibleContext().setAccessibleName("Abrir seletor de emojis");
        botaoEmoji.addActionListener(
                e -> seletorEmojis.show(botaoEmoji, 0, -seletorEmojis.getPreferredSize().height));

        botaoFigurinha.setName("botao-figurinha");
        botaoFigurinha.setToolTipText("Enviar uma figurinha");
        botaoFigurinha.getAccessibleContext().setAccessibleName("Abrir seletor de figurinhas");
        botaoFigurinha.addActionListener(
                e -> seletorFigurinhas.show(botaoFigurinha, 0, -seletorFigurinhas.getPreferredSize().height));

        campoTexto.setName("campo-texto");
        campoTexto.getAccessibleContext().setAccessibleName("Mensagem");
        campoTexto.setToolTipText("Digite a mensagem e pressione Enter");
        campoTexto.addActionListener(e -> enviar()); // Enter

        botaoEnviar.setName("botao-enviar");
        botaoEnviar.addActionListener(e -> enviar());

        JPanel linha = new JPanel(new BorderLayout(4, 0));
        JPanel esquerda = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        esquerda.add(botaoEmoji);
        esquerda.add(botaoFigurinha);
        linha.add(esquerda, BorderLayout.WEST);
        linha.add(campoTexto, BorderLayout.CENTER);
        linha.add(botaoEnviar, BorderLayout.EAST);
        linha.setAlignmentX(LEFT_ALIGNMENT);

        rodape.add(rotuloAviso);
        rodape.add(Box.createVerticalStrut(2));
        rodape.add(linha);
        return rodape;
    }

    // ------------------------------------------------------------------
    // Envio
    // ------------------------------------------------------------------

    /** Acao unica do botao Enviar e da tecla Enter. */
    void enviar() {
        if (!autenticado) {
            mostrarAviso("Sem conexão com o servidor. Aguarde a reconexão para enviar.");
            return;
        }
        tratar(envio.acionarEnviar());
    }

    private void enviarFigurinha(String id) {
        if (!autenticado) {
            mostrarAviso("Sem conexão com o servidor. Aguarde a reconexão para enviar.");
            return;
        }
        tratar(envio.enviarFigurinha(id));
    }

    private void tratar(ResultadoEnvio resultado) {
        if (resultado.enviado()) {
            mostrarAviso(null);
            resultado.futuro().whenComplete((r, erro) -> {
                if (erro != null) {
                    SwingUtilities.invokeLater(
                            () -> mostrarAviso("Não foi possível enviar: " + MensagensUsuario.paraErro(erro)));
                }
            });
            return;
        }
        switch (resultado.motivo()) {
            case EM_BRANCO -> {
                // Mantem o estado atual, sem aviso.
            }
            case MUITO_LONGO -> mostrarAviso(
                    "A mensagem passa de " + ControladorEnvio.MAXIMO_PONTOS_DE_CODIGO + " caracteres. Encurte o texto.");
            case FIGURINHA_INVALIDA -> mostrarAviso("Figurinha inválida.");
            case FALHA -> mostrarAviso("Não foi possível enviar: " + MensagensUsuario.paraErro(resultado.erro()));
        }
    }

    /** Insere o emoji na posicao do cursor, sem converter nem enviar. */
    void inserirEmoji(String emoji) {
        int posicao = Math.min(campoTexto.getCaretPosition(), campoTexto.getText().length());
        int nova = envio.inserirEmojiNoCampo(emoji, posicao);
        campoTexto.requestFocusInWindow();
        campoTexto.setCaretPosition(nova);
    }

    // ------------------------------------------------------------------
    // Atualizacao pela sessao (EDT)
    // ------------------------------------------------------------------

    /** Inclui mensagens (ordenadas e sem duplicatas) e rola ate o fim se o usuario ja estava la. */
    public void adicionarMensagens(Collection<MensagemRecebida> novas) {
        JScrollBar barra = rolagem.getVerticalScrollBar();
        boolean noFim = barra.getValue() + barra.getVisibleAmount() >= barra.getMaximum() - 24;
        boolean propria = novas.stream().anyMatch(m -> m.remetente().login().equals(perfil.login()));
        if (painelMensagens.adicionar(novas) > 0 && (noFim || propria)) {
            SwingUtilities.invokeLater(() -> barra.setValue(barra.getMaximum()));
        }
    }

    /** Atualiza o indicador de estado, o botao de nova tentativa e a habilitacao do envio. */
    public void mostrarEstado(EstadoConexao estado, Map<String, Object> detalhe) {
        rotuloEstado.setText(MensagensUsuario.paraEstado(estado, detalhe));
        autenticado = estado == EstadoConexao.AUTENTICADO;
        botaoEnviar.setEnabled(autenticado);
        botaoFigurinha.setEnabled(autenticado);
        botaoNovaTentativa.setVisible(estado == EstadoConexao.DESCONECTADO);
        rotuloEstado.setForeground(
                autenticado ? new Color(0x2E7D32) : estado == EstadoConexao.DESCONECTADO ? new Color(0xB00020) : new Color(0xE65100));
    }

    /** Mostra (ou, com {@code null}, apaga) o aviso acima do campo de texto. */
    public void mostrarAviso(String texto) {
        rotuloAviso.setText(texto == null || texto.isBlank() ? " " : texto);
    }

    public void focarCampo() {
        campoTexto.requestFocusInWindow();
    }

    /** Adapta o {@code JTextField} ao {@link CampoDeTexto} do controlador de envio. */
    private static final class CampoSwing implements CampoDeTexto {
        private final JTextField campo;

        CampoSwing(JTextField campo) {
            this.campo = campo;
        }

        @Override
        public String ler() {
            return campo.getText();
        }

        @Override
        public void escrever(String texto) {
            campo.setText(texto);
        }
    }
}
