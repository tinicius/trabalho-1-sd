package br.cefet.sd.cliente.desktop.sessao;

import br.cefet.sd.cliente.desktop.rede.EstadoConexao;
import br.cefet.sd.cliente.desktop.rede.MensagemRecebida;
import br.cefet.sd.comum.protocolo.Perfil;
import java.util.List;
import java.util.Map;

/**
 * Saida do {@link ControladorSessao} para a interface. Os metodos sao chamados em threads da
 * conexao (nao na EDT) e em ordem; a implementacao Swing repassa cada chamada para a EDT com {@code
 * invokeLater}, o que preserva a ordem.
 */
public interface ObservadorSessao {

    /** Uma requisicao de login ou cadastro comecou ({@code true}) ou terminou ({@code false}). */
    default void aoOcupado(boolean ocupado) {}

    /** Login recusado ou impossivel; {@code mensagem} ja e amigavel. */
    default void aoLoginRecusado(String mensagem) {}

    /** Cadastro recusado; {@code campo} e o nome do campo no Protocolo, ou {@code null}. */
    default void aoCadastroRecusado(String mensagem, String campo) {}

    default void aoCadastroConcluido(String login) {}

    /**
     * Autenticacao confirmada pelo Middleware (unico momento em que a tela da sala pode existir).
     * {@code locais} e o Historico_Local ja existente, ordenado por (ts, mid).
     */
    default void aoAutenticar(Perfil perfil, List<MensagemRecebida> locais) {}

    /** Mensagens ainda nao exibidas (sem duplicatas), vindas ao vivo ou do historico do servidor. */
    default void aoNovasMensagens(List<MensagemRecebida> novas) {}

    /** Mudanca de estado da conexao enquanto a sala esta aberta. */
    default void aoEstadoConexao(EstadoConexao estado, Map<String, Object> detalhe) {}

    /** A sessao terminou e a interface deve voltar ao login; {@code aviso} pode ser {@code null}. */
    default void aoSessaoEncerrada(String aviso) {}

    /** Aviso nao fatal para a sala (por exemplo, falha ao gravar o historico). */
    default void aoAviso(String texto) {}
}
