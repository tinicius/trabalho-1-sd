package br.cefet.sd.cliente.desktop.envio;

import br.cefet.sd.comum.protocolo.MensagemChat;
import java.util.concurrent.CompletableFuture;

/**
 * Funcao de envio injetada no {@link ControladorEnvio}. Normalmente {@code conexao::enviarMensagem}
 * ({@code ConexaoTcp}), que cifra o {@code conteudo} com a Chave_Transito.
 */
@FunctionalInterface
public interface EnviadorMensagens {
    CompletableFuture<?> enviar(MensagemChat.Tipo tipo, String conteudo);
}
