package br.cefet.sd.cliente.desktop.sessao;

import br.cefet.sd.cliente.desktop.rede.ConexaoTcp;
import br.cefet.sd.cliente.desktop.rede.ConexaoTcp.ResultadoLogin;
import br.cefet.sd.comum.protocolo.MensagemChat;
import java.util.concurrent.CompletableFuture;

/** {@link ServicoChat} sobre a {@link ConexaoTcp} do Cliente_Desktop. */
public final class ServicoChatTcp implements ServicoChat {

    private final ConexaoTcp conexao;

    public ServicoChatTcp(ConexaoTcp conexao) {
        if (conexao == null) {
            throw new IllegalArgumentException("A conexao e obrigatoria");
        }
        this.conexao = conexao;
    }

    @Override
    public CompletableFuture<ResultadoLogin> login(String login, String senha) {
        return conexao.login(login, senha);
    }

    @Override
    public CompletableFuture<?> registrar(
            String login, String apelido, String senha, String avatar, String cor) {
        return conexao.registrar(login, apelido, senha, avatar, cor);
    }

    @Override
    public CompletableFuture<?> enviar(MensagemChat.Tipo tipo, String conteudo) {
        return conexao.enviarMensagem(tipo, conteudo);
    }

    @Override
    public CompletableFuture<Void> logout() {
        return conexao.logout();
    }

    @Override
    public boolean tentarNovamente() {
        return conexao.tentarNovamente();
    }

    @Override
    public void fechar() {
        conexao.fechar();
    }
}
