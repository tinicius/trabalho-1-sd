package br.cefet.sd.cliente.desktop.sessao;

import br.cefet.sd.cliente.desktop.rede.ConexaoTcp.ResultadoLogin;
import br.cefet.sd.cliente.desktop.rede.ErroRequisicao;
import br.cefet.sd.comum.protocolo.MensagemChat;
import java.util.concurrent.CompletableFuture;

/**
 * O que o {@link ControladorSessao} precisa da conexao com o Middleware. Na aplicacao e a {@link
 * ServicoChatTcp} (sobre a {@code ConexaoTcp}); nos testes, um servico falso.
 *
 * <p>Todos os metodos devolvem futuros que falham com {@link ErroRequisicao}.
 */
public interface ServicoChat {

    CompletableFuture<ResultadoLogin> login(String login, String senha);

    CompletableFuture<?> registrar(String login, String apelido, String senha, String avatar, String cor);

    /** {@code send_message}; o {@code conteudo} e cifrado pela implementacao. */
    CompletableFuture<?> enviar(MensagemChat.Tipo tipo, String conteudo);

    CompletableFuture<Void> logout();

    /** Retoma a reconexao depois de esgotadas as tentativas; {@code false} se nao ha o que retomar. */
    boolean tentarNovamente();

    /** Fecha a conexao sem reconectar. */
    void fechar();

    /**
     * Servico usado quando a Chave_Transito esta ausente ou invalida: toda operacao falha com
     * {@link ErroRequisicao#SEM_CONEXAO}, sem tocar na rede.
     */
    static ServicoChat indisponivel() {
        return new ServicoChat() {
            private <T> CompletableFuture<T> falha() {
                return CompletableFuture.failedFuture(
                        new ErroRequisicao(ErroRequisicao.SEM_CONEXAO, "Chave de transito indisponivel"));
            }

            @Override
            public CompletableFuture<ResultadoLogin> login(String login, String senha) {
                return falha();
            }

            @Override
            public CompletableFuture<?> registrar(
                    String login, String apelido, String senha, String avatar, String cor) {
                return falha();
            }

            @Override
            public CompletableFuture<?> enviar(MensagemChat.Tipo tipo, String conteudo) {
                return falha();
            }

            @Override
            public CompletableFuture<Void> logout() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public boolean tentarNovamente() {
                return false;
            }

            @Override
            public void fechar() {}
        };
    }
}
