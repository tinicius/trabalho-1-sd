package br.cefet.sd.cliente.desktop.rede;

import br.cefet.sd.comum.protocolo.Perfil;
import java.util.List;
import java.util.Map;

/**
 * Eventos publicados pela {@link ConexaoTcp} ao ouvinte, na ordem em que ocorrem. O ouvinte roda em
 * uma thread propria da conexao: a interface Swing deve repassar para a EDT com
 * {@code SwingUtilities.invokeLater}. Nenhum evento carrega senha, chave ou conteudo legivel de erro.
 */
public sealed interface EventoConexao {

    /**
     * Mudanca de estado. {@code detalhe} traz {@code tentativa}, {@code maximo} e {@code atraso_ms}
     * em "reconectando" e {@code motivo} em "desconectado".
     */
    record EventoEstado(EstadoConexao estado, Map<String, Object> detalhe) implements EventoConexao {}

    record EventoMensagem(MensagemRecebida mensagem) implements EventoConexao {}

    record EventoHistorico(int lote, boolean ultimo, List<MensagemRecebida> mensagens)
            implements EventoConexao {}

    /** Relogin automatico bem-sucedido apos reconexao. */
    record EventoReautenticado(Perfil perfil) implements EventoConexao {}

    /**
     * Falha que nao pertence a nenhuma requisicao (quadro invalido, mensagem que nao decifra, erro de
     * rede).
     */
    record EventoErro(Exception erro, String contexto) implements EventoConexao {}
}
