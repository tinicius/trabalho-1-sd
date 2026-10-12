package br.cefet.sd.cliente.desktop.ui;

import br.cefet.sd.cliente.desktop.rede.EstadoConexao;
import br.cefet.sd.cliente.desktop.rede.MensagemRecebida;
import br.cefet.sd.cliente.desktop.recursos.CatalogoRecursos;
import br.cefet.sd.cliente.desktop.sessao.AcoesSala;
import br.cefet.sd.cliente.desktop.sessao.ObservadorSessao;
import br.cefet.sd.comum.protocolo.Perfil;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Liga a sessao (threads da conexao) a interface: cada chamada vai para a EDT em ordem, e so
 * {@link #aoAutenticar} cria a {@link TelaSala} e a adiciona ao {@link PainelPrincipal}.
 */
public final class ObservadorSwing implements ObservadorSessao {

    private final PainelPrincipal principal;
    private final CatalogoRecursos catalogo;
    private final Consumer<Runnable> naEdt;
    private volatile AcoesSala acoesSala;

    /** Usa {@code SwingUtilities::invokeLater} para chegar a EDT. */
    public ObservadorSwing(PainelPrincipal principal, CatalogoRecursos catalogo) {
        this(principal, catalogo, SwingUtilities::invokeLater);
    }

    /**
     * @param naEdt executa um {@code Runnable} na EDT, em ordem; em testes, {@code Runnable::run}
     */
    public ObservadorSwing(PainelPrincipal principal, CatalogoRecursos catalogo, Consumer<Runnable> naEdt) {
        this.principal = principal;
        this.catalogo = catalogo;
        this.naEdt = naEdt;
    }

    /** Define as acoes que a tela da sala vai chamar (o {@code ControladorSessao}). */
    public void definirAcoes(AcoesSala acoes) {
        this.acoesSala = acoes;
    }

    @Override
    public void aoOcupado(boolean ocupado) {
        naEdt.accept(() -> principal.telaLogin().definirOcupado(ocupado));
    }

    @Override
    public void aoLoginRecusado(String mensagem) {
        naEdt.accept(() -> principal.telaLogin().mostrarErroLogin(mensagem));
    }

    @Override
    public void aoCadastroRecusado(String mensagem, String campo) {
        naEdt.accept(() -> principal.telaLogin().mostrarErroCadastro(mensagem));
    }

    @Override
    public void aoCadastroConcluido(String login) {
        naEdt.accept(() -> principal.telaLogin().cadastroConcluido(login));
    }

    @Override
    public void aoAutenticar(Perfil perfil, List<MensagemRecebida> locais) {
        naEdt.accept(() -> {
            TelaSala sala = new TelaSala(perfil, catalogo, acoesSala);
            principal.mostrarSala(sala);
            sala.adicionarMensagens(locais);
            sala.focarCampo();
        });
    }

    @Override
    public void aoNovasMensagens(List<MensagemRecebida> novas) {
        naEdt.accept(() -> {
            TelaSala sala = principal.sala();
            if (sala != null) {
                sala.adicionarMensagens(novas);
            }
        });
    }

    @Override
    public void aoEstadoConexao(EstadoConexao estado, Map<String, Object> detalhe) {
        naEdt.accept(() -> {
            TelaSala sala = principal.sala();
            if (sala != null) {
                sala.mostrarEstado(estado, detalhe);
            }
        });
    }

    @Override
    public void aoSessaoEncerrada(String aviso) {
        naEdt.accept(() -> {
            principal.mostrarLogin();
            principal.telaLogin().reiniciar();
            if (aviso != null) {
                principal.telaLogin().mostrarAvisoLogin(aviso);
            }
        });
    }

    @Override
    public void aoAviso(String texto) {
        naEdt.accept(() -> {
            TelaSala sala = principal.sala();
            if (sala != null) {
                sala.mostrarAviso(texto);
            }
        });
    }
}
