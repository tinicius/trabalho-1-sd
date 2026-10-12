package br.cefet.sd.cliente.desktop.ui;

import br.cefet.sd.cliente.desktop.recursos.CatalogoRecursos;
import br.cefet.sd.cliente.desktop.sessao.DadosCadastro;
import br.cefet.sd.cliente.desktop.sessao.FormatadorMensagem;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;

/**
 * Primeira tela do Cliente_Desktop: formulario de login e senha, com acesso ao cadastro
 * (login, apelido, senha, avatar e cor). Nao conhece a rede: delega a {@link Acoes}. Usar somente
 * na EDT.
 *
 * <p>Enquanto uma requisicao esta em andamento, os botoes ficam desabilitados
 * ({@link #definirOcupado}). Se a Chave_Transito esta ausente ou invalida, o formulario fica
 * bloqueado e mostra a orientacao ao usuario.
 */
public final class TelaLogin extends JPanel {
    private static final long serialVersionUID = 1L;

    private static final String CARTAO_ENTRAR = "entrar";
    private static final String CARTAO_CADASTRO = "cadastro";
    private static final Color COR_ERRO = new Color(0xB00020);
    private static final Color COR_INFO = new Color(0x2E7D32);
    private static final int COR_INICIAL = 0x1E88E5;

    /** Acoes disparadas pelos formularios. */
    public interface Acoes {
        void entrar(String login, String senha);

        void cadastrar(DadosCadastro dados);
    }

    private final Acoes acoes;
    private final CardLayout cartoes = new CardLayout();
    private final JPanel conteudo = new JPanel(cartoes);
    private final boolean bloqueado;

    // formulario de login
    private final JTextField campoLogin = new JTextField(20);
    private final JPasswordField campoSenha = new JPasswordField(20);
    private final JButton botaoEntrar = new JButton("Entrar");
    private final JButton botaoIrCadastro = new JButton("Criar conta");
    private final JLabel mensagemLogin = new JLabel(" ");

    // formulario de cadastro
    private final JTextField cadLogin = new JTextField(20);
    private final JTextField cadApelido = new JTextField(20);
    private final JPasswordField cadSenha = new JPasswordField(20);
    private final JPasswordField cadConfirmacao = new JPasswordField(20);
    private final JButton botaoCor = new JButton("Escolher cor…");
    private final JLabel previaApelido = new JLabel("Seu apelido");
    private final JButton botaoCadastrar = new JButton("Cadastrar");
    private final JButton botaoVoltar = new JButton("Voltar");
    private final JLabel mensagemCadastro = new JLabel(" ");
    private final Map<String, JToggleButton> botoesAvatar = new java.util.LinkedHashMap<>();
    private Color corEscolhida = new Color(COR_INICIAL);

    /**
     * @param bloqueio texto de orientacao quando o login esta impedido (chave ausente); {@code null}
     *     se o formulario esta liberado
     */
    public TelaLogin(CatalogoRecursos catalogo, Acoes acoes, String bloqueio) {
        super(new BorderLayout());
        this.acoes = acoes;
        this.bloqueado = bloqueio != null;
        setName("tela-login");

        conteudo.add(criarFormularioLogin(bloqueio), CARTAO_ENTRAR);
        conteudo.add(criarFormularioCadastro(catalogo), CARTAO_CADASTRO);
        add(conteudo, BorderLayout.CENTER);
        atualizarCor();
        definirOcupado(false);
    }

    // ------------------------------------------------------------------
    // Formulario de login
    // ------------------------------------------------------------------

    private JComponent criarFormularioLogin(String bloqueio) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setName("formulario-login");
        form.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
        GridBagConstraints c = restricoes();

        JLabel titulo = new JLabel("Comunicador");
        titulo.setFont(titulo.getFont().deriveFont(Font.BOLD, 24f));
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        form.add(titulo, c);

        JLabel subtitulo = new JLabel("Entre com seu login e senha para acessar a sala.");
        c.gridy++;
        form.add(subtitulo, c);

        if (bloqueio != null) {
            c.gridy++;
            form.add(areaDeTexto(bloqueio, "orientacao-chave", COR_ERRO), c);
        }

        c.gridwidth = 1;
        c.gridy++;
        form.add(rotulo("Login:", campoLogin, 'L'), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(campoLogin, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        form.add(rotulo("Senha:", campoSenha, 'S'), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(campoSenha, c);

        campoLogin.setName("login");
        campoSenha.setName("senha");
        campoLogin.getAccessibleContext().setAccessibleName("Login");
        campoSenha.getAccessibleContext().setAccessibleName("Senha");
        campoLogin.addActionListener(e -> campoSenha.requestFocusInWindow());
        campoSenha.addActionListener(e -> aoEntrar());

        mensagemLogin.setName("mensagem-login");
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        form.add(mensagemLogin, c);

        botaoEntrar.setName("botao-entrar");
        botaoEntrar.addActionListener(e -> aoEntrar());
        botaoIrCadastro.setName("botao-ir-cadastro");
        botaoIrCadastro.addActionListener(e -> {
            mensagemCadastro.setText(" ");
            cartoes.show(conteudo, CARTAO_CADASTRO);
            cadLogin.requestFocusInWindow();
        });
        JPanel botoes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        botoes.add(botaoEntrar);
        botoes.add(botaoIrCadastro);
        c.gridy++;
        form.add(botoes, c);

        JPanel externo = new JPanel(new BorderLayout());
        externo.add(form, BorderLayout.NORTH);
        return externo;
    }

    private void aoEntrar() {
        if (bloqueado || !botaoEntrar.isEnabled()) {
            return;
        }
        mostrarAvisoLogin(null);
        acoes.entrar(campoLogin.getText(), new String(campoSenha.getPassword()));
    }

    // ------------------------------------------------------------------
    // Formulario de cadastro
    // ------------------------------------------------------------------

    private JComponent criarFormularioCadastro(CatalogoRecursos catalogo) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setName("formulario-cadastro");
        form.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
        GridBagConstraints c = restricoes();

        JLabel titulo = new JLabel("Criar conta");
        titulo.setFont(titulo.getFont().deriveFont(Font.BOLD, 20f));
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        form.add(titulo, c);

        c.gridwidth = 1;
        adicionarLinha(form, c, "Login:", cadLogin, 'L', "cad-login", "Login");
        adicionarLinha(form, c, "Apelido:", cadApelido, 'A', "cad-apelido", "Apelido");
        adicionarLinha(form, c, "Senha:", cadSenha, 'S', "cad-senha", "Senha");
        adicionarLinha(form, c, "Repita a senha:", cadConfirmacao, 'R', "cad-confirmacao", "Confirmação da senha");

        // avatar
        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        form.add(new JLabel("Avatar:"), c);
        JPanel grade = new JPanel(new GridLayout(0, 6, 4, 4));
        grade.setName("grade-avatares");
        ButtonGroup grupo = new ButtonGroup();
        for (Map.Entry<String, BufferedImage> avatar : catalogo.avatares().entrySet()) {
            JToggleButton b = new JToggleButton(Imagens.icone(avatar.getValue(), 40, 40));
            b.setName(avatar.getKey());
            b.setToolTipText(avatar.getKey());
            b.setMargin(new Insets(2, 2, 2, 2));
            b.getAccessibleContext().setAccessibleName("Avatar " + avatar.getKey());
            grupo.add(b);
            grade.add(b);
            botoesAvatar.put(avatar.getKey(), b);
        }
        if (!botoesAvatar.isEmpty()) {
            botoesAvatar.values().iterator().next().setSelected(true);
        }
        c.gridx = 1;
        c.weightx = 1;
        form.add(grade, c);

        // cor
        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        form.add(new JLabel("Cor do apelido:"), c);
        botaoCor.setName("cad-cor");
        botaoCor.getAccessibleContext().setAccessibleName("Escolher a cor do apelido");
        botaoCor.addActionListener(e -> {
            Color escolhida = JColorChooser.showDialog(this, "Cor do apelido", corEscolhida);
            if (escolhida != null) {
                corEscolhida = escolhida;
                atualizarCor();
            }
        });
        previaApelido.setName("cad-previa");
        previaApelido.setFont(previaApelido.getFont().deriveFont(Font.BOLD));
        JPanel linhaCor = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        linhaCor.add(botaoCor);
        linhaCor.add(previaApelido);
        c.gridx = 1;
        c.weightx = 1;
        form.add(linhaCor, c);

        cadApelido.getDocument().addDocumentListener(new SimplesDocumentListener(this::atualizarCor));

        mensagemCadastro.setName("mensagem-cadastro");
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        form.add(mensagemCadastro, c);

        botaoCadastrar.setName("botao-cadastrar");
        botaoCadastrar.addActionListener(e -> aoCadastrar());
        botaoVoltar.setName("botao-voltar-login");
        botaoVoltar.addActionListener(e -> {
            cartoes.show(conteudo, CARTAO_ENTRAR);
            campoLogin.requestFocusInWindow();
        });
        JPanel botoes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        botoes.add(botaoCadastrar);
        botoes.add(botaoVoltar);
        c.gridy++;
        form.add(botoes, c);

        JPanel externo = new JPanel(new BorderLayout());
        externo.add(form, BorderLayout.NORTH);
        return externo;
    }

    private void adicionarLinha(
            JPanel form, GridBagConstraints c, String texto, JComponent campo, char mnemonico, String nome, String acessivel) {
        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        form.add(rotulo(texto, campo, mnemonico), c);
        c.gridx = 1;
        c.weightx = 1;
        campo.setName(nome);
        campo.getAccessibleContext().setAccessibleName(acessivel);
        form.add(campo, c);
    }

    private void aoCadastrar() {
        if (bloqueado || !botaoCadastrar.isEnabled()) {
            return;
        }
        mensagemCadastro.setText(" ");
        acoes.cadastrar(new DadosCadastro(
                cadLogin.getText(),
                cadApelido.getText(),
                new String(cadSenha.getPassword()),
                new String(cadConfirmacao.getPassword()),
                avatarEscolhido(),
                FormatadorMensagem.hexDe(corEscolhida.getRGB())));
    }

    private String avatarEscolhido() {
        for (Map.Entry<String, JToggleButton> e : botoesAvatar.entrySet()) {
            if (e.getValue().isSelected()) {
                return e.getKey();
            }
        }
        return null;
    }

    private void atualizarCor() {
        previaApelido.setForeground(corEscolhida);
        String apelido = cadApelido.getText().strip();
        previaApelido.setText(apelido.isEmpty() ? "Seu apelido" : apelido);
        botaoCor.setText("Escolher cor… (" + FormatadorMensagem.hexDe(corEscolhida.getRGB()) + ")");
    }

    // ------------------------------------------------------------------
    // Atualizacao pela sessao (EDT)
    // ------------------------------------------------------------------

    /** Desabilita os botoes durante uma requisicao (e sempre, se o login esta bloqueado). */
    public void definirOcupado(boolean ocupado) {
        boolean liberado = !ocupado && !bloqueado;
        botaoEntrar.setEnabled(liberado);
        botaoIrCadastro.setEnabled(!ocupado);
        botaoCadastrar.setEnabled(liberado);
        botaoVoltar.setEnabled(!ocupado);
        campoLogin.setEnabled(!bloqueado);
        campoSenha.setEnabled(!bloqueado);
    }

    /** Erro do login (vermelho). */
    public void mostrarErroLogin(String texto) {
        mensagemLogin.setForeground(COR_ERRO);
        mensagemLogin.setText(vazioComo(texto));
        campoSenha.setText("");
        if (!bloqueado) {
            campoSenha.requestFocusInWindow();
        }
    }

    /** Aviso informativo (verde) ou, com {@code null}, apaga a mensagem. */
    public void mostrarAvisoLogin(String texto) {
        mensagemLogin.setForeground(COR_INFO);
        mensagemLogin.setText(vazioComo(texto));
    }

    public void mostrarErroCadastro(String texto) {
        mensagemCadastro.setForeground(COR_ERRO);
        mensagemCadastro.setText(vazioComo(texto));
    }

    /** Cadastro aceito: volta ao login com o login preenchido e um aviso. */
    public void cadastroConcluido(String login) {
        limparCadastro();
        cartoes.show(conteudo, CARTAO_ENTRAR);
        campoLogin.setText(login);
        campoSenha.setText("");
        mostrarAvisoLogin("Conta criada. Entre com seu login e senha.");
        campoSenha.requestFocusInWindow();
    }

    /** Volta ao estado inicial depois de um logout: limpa a senha e libera o formulario. */
    public void reiniciar() {
        campoSenha.setText("");
        cartoes.show(conteudo, CARTAO_ENTRAR);
        definirOcupado(false);
        mostrarAvisoLogin(null);
    }

    private void limparCadastro() {
        cadLogin.setText("");
        cadApelido.setText("");
        cadSenha.setText("");
        cadConfirmacao.setText("");
        mensagemCadastro.setText(" ");
    }

    // ------------------------------------------------------------------
    // Utilitarios
    // ------------------------------------------------------------------

    private static String vazioComo(String texto) {
        return texto == null || texto.isBlank() ? " " : texto;
    }

    private static GridBagConstraints restricoes() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static JLabel rotulo(String texto, JComponent campo, char mnemonico) {
        JLabel r = new JLabel(texto);
        r.setLabelFor(campo);
        r.setDisplayedMnemonic(mnemonico);
        return r;
    }

    private static JTextArea areaDeTexto(String texto, String nome, Color cor) {
        JTextArea area = new JTextArea(texto);
        area.setName(nome);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setForeground(cor);
        area.setPreferredSize(new Dimension(380, 72));
        return area;
    }

    public JTextField campoLogin() {
        return campoLogin;
    }

    /** Adaptador que reduz {@code DocumentListener} a um unico {@code Runnable}. */
    private static final class SimplesDocumentListener implements javax.swing.event.DocumentListener {
        private final Runnable acao;

        SimplesDocumentListener(Runnable acao) {
            this.acao = acao;
        }

        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            acao.run();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            acao.run();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            acao.run();
        }
    }
}
