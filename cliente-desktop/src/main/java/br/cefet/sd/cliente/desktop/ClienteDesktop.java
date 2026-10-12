package br.cefet.sd.cliente.desktop;

import br.cefet.sd.cliente.desktop.config.ConfigCliente;
import br.cefet.sd.cliente.desktop.erros.ArmazenamentoArquivo;
import br.cefet.sd.cliente.desktop.erros.BufferOfflineErros;
import br.cefet.sd.cliente.desktop.erros.ConexaoErros;
import br.cefet.sd.cliente.desktop.erros.ReportadorErros;
import br.cefet.sd.cliente.desktop.historico.HistoricoLocal;
import br.cefet.sd.cliente.desktop.rede.ConexaoTcp;
import br.cefet.sd.cliente.desktop.recursos.CatalogoRecursos;
import br.cefet.sd.cliente.desktop.sessao.ControladorSessao;
import br.cefet.sd.cliente.desktop.sessao.ServicoChat;
import br.cefet.sd.cliente.desktop.sessao.ServicoChatTcp;
import br.cefet.sd.cliente.desktop.ui.ObservadorSwing;
import br.cefet.sd.cliente.desktop.ui.PainelPrincipal;
import br.cefet.sd.cliente.desktop.ui.TelaLogin;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * Classe principal do Cliente_Desktop.
 *
 * <p>Ordem de partida: configuracao ({@code <raiz>/.comunicador}), {@link ReportadorErros} com captura
 * global de excecoes (inclui a EDT), recursos ({@code recursos/}) e, na EDT, a montagem da
 * interface. A primeira tela e o formulario de login; a sala so abre depois que o
 * Middleware confirma a autenticacao.
 *
 * <p>Enderecos do Middleware: {@code --host=H} e {@code --porta=P} na linha de comando, ou as
 * variaveis {@value #VARIAVEL_HOST} e {@value #VARIAVEL_PORTA}; padrao {@code localhost:9000}.
 */
public final class ClienteDesktop {

    public static final String VARIAVEL_HOST = "COMUNICADOR_HOST";
    public static final String VARIAVEL_PORTA = "COMUNICADOR_PORTA";

    private ClienteDesktop() {}

    /** Pecas da interface e da sessao, montadas sem janela (permite instanciar em modo headless). */
    public record Montagem(
            PainelPrincipal painel, TelaLogin telaLogin, ControladorSessao controlador, ObservadorSwing observador) {}

    // ------------------------------------------------------------------
    // main
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        ConfigCliente config;
        try {
            config = ConfigCliente.carregar();
        } catch (IOException e) {
            erroFatal("Não foi possível preparar a pasta de configuração (<raiz>/.comunicador): " + e.getMessage());
            return;
        }
        ConexaoTcp.Opcoes opcoes;
        try {
            opcoes = opcoesRede(args, System.getenv());
        } catch (IllegalArgumentException e) {
            erroFatal(e.getMessage());
            return;
        }
        ReportadorErros reportador = criarReportador(config);
        ReportadorErros.instalarCapturaGlobal(reportador);
        reportador.iniciar();
        ConexaoTcp.Opcoes opcoesFinais = opcoes;
        SwingUtilities.invokeLater(() -> iniciarInterface(config, opcoesFinais, reportador));
    }

    /** Reportador com Buffer_Offline_Erros em {@code <raiz>/.comunicador/desktop/erros_pendentes.txt}. */
    static ReportadorErros criarReportador(ConfigCliente config) {
        BufferOfflineErros buffer = new BufferOfflineErros(ArmazenamentoArquivo.em(config.diretorioDesktop()));
        return new ReportadorErros(buffer, null, ReportadorErros.Opcoes.padrao().comDispositivo(config.dispositivo()));
    }

    /**
     * Enderecos do Middleware a partir da linha de comando e do ambiente; a linha de comando vence.
     *
     * @throws IllegalArgumentException se a porta nao for um numero entre 1 e 65535
     */
    public static ConexaoTcp.Opcoes opcoesRede(String[] args, Map<String, String> ambiente) {
        String host = ambiente.get(VARIAVEL_HOST);
        String porta = ambiente.get(VARIAVEL_PORTA);
        for (String arg : args == null ? new String[0] : args) {
            if (arg.startsWith("--host=")) {
                host = arg.substring("--host=".length());
            } else if (arg.startsWith("--porta=")) {
                porta = arg.substring("--porta=".length());
            }
        }
        ConexaoTcp.Opcoes opcoes = ConexaoTcp.Opcoes.padrao();
        String hostFinal = host == null || host.isBlank() ? opcoes.host() : host.strip();
        int portaFinal = opcoes.porta();
        if (porta != null && !porta.isBlank()) {
            try {
                portaFinal = Integer.parseInt(porta.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Porta invalida: informe um numero entre 1 e 65535.");
            }
            if (portaFinal < 1 || portaFinal > 65535) {
                throw new IllegalArgumentException("Porta invalida: informe um numero entre 1 e 65535.");
            }
        }
        return opcoes.comEndereco(hostFinal, portaFinal);
    }

    // ------------------------------------------------------------------
    // Montagem
    // ------------------------------------------------------------------

    /**
     * Cria a conexao (se a Chave_Transito for valida), o controlador da sessao e as telas, ligados
     * entre si. Nao cria janela. Chamar na EDT.
     *
     * @param reportador reportador de erros; recebe a conexao para enviar {@code error_report}
     * @param naEdt como chegar a EDT a partir das threads da conexao
     */
    public static Montagem montar(
            ConfigCliente config,
            CatalogoRecursos catalogo,
            ConexaoTcp.Opcoes opcoes,
            ReportadorErros reportador,
            Consumer<Runnable> naEdt) {
        AtomicReference<ControladorSessao> referencia = new AtomicReference<>();
        ServicoChat servico;
        if (config.loginPermitido()) {
            ConexaoTcp conexao = new ConexaoTcp(
                    config.exigirChaveTransito(),
                    config.dispositivo(),
                    evento -> {
                        ControladorSessao controlador = referencia.get();
                        if (controlador != null) {
                            controlador.aoEvento(evento);
                        }
                    },
                    opcoes);
            reportador.definirConexao(ConexaoErros.de(conexao));
            servico = new ServicoChatTcp(conexao);
        } else {
            servico = ServicoChat.indisponivel();
        }

        TelaLogin telaLogin = new TelaLogin(
                catalogo,
                new TelaLogin.Acoes() {
                    @Override
                    public void entrar(String login, String senha) {
                        referencia.get().entrar(login, senha);
                    }

                    @Override
                    public void cadastrar(br.cefet.sd.cliente.desktop.sessao.DadosCadastro dados) {
                        referencia.get().cadastrar(dados);
                    }
                },
                config.orientacaoChave().orElse(null));
        PainelPrincipal painel = new PainelPrincipal(telaLogin);
        ObservadorSwing observador = new ObservadorSwing(painel, catalogo, naEdt);
        ControladorSessao controlador = new ControladorSessao(
                servico,
                login -> HistoricoLocal.abrir(config.diretorioDesktop(), login, config.exigirChaveTransito()),
                reportador,
                observador);
        observador.definirAcoes(controlador);
        referencia.set(controlador);
        return new Montagem(painel, telaLogin, controlador, observador);
    }

    // ------------------------------------------------------------------
    // Interface
    // ------------------------------------------------------------------

    private static void iniciarInterface(ConfigCliente config, ConexaoTcp.Opcoes opcoes, ReportadorErros reportador) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // segue com o visual padrao do Swing
        }
        Optional<Path> raiz = CatalogoRecursos.localizarRaiz();
        if (raiz.isEmpty()) {
            erroFatal("A pasta recursos/ não foi encontrada. Execute o aplicativo a partir da pasta do projeto "
                    + "ou defina a variável " + CatalogoRecursos.VARIAVEL_RECURSOS + ".");
            return;
        }
        CatalogoRecursos catalogo;
        try {
            catalogo = CatalogoRecursos.carregar(raiz.get());
        } catch (IOException e) {
            reportador.reportar(e, "recursos");
            erroFatal("Os arquivos da pasta recursos/ estão incompletos ou inválidos.");
            return;
        }

        Montagem montagem = montar(config, catalogo, opcoes, reportador, SwingUtilities::invokeLater);
        JFrame janela = new JFrame("Comunicador");
        janela.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        janela.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                montagem.controlador().encerrar();
                reportador.parar();
                janela.dispose();
                System.exit(0);
            }
        });
        janela.setContentPane(montagem.painel());
        janela.setMinimumSize(new Dimension(480, 520));
        janela.setSize(640, 720);
        janela.setLocationRelativeTo(null);
        janela.setVisible(true);
        montagem.telaLogin().campoLogin().requestFocusInWindow();
    }

    private static void erroFatal(String mensagem) {
        System.err.println("[Comunicador] " + mensagem);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, mensagem, "Comunicador", JOptionPane.ERROR_MESSAGE);
        }
        System.exit(1);
    }
}
