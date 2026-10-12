package br.cefet.sd.cliente.desktop.rede;

import br.cefet.sd.comum.protocolo.MensagemChat;
import br.cefet.sd.comum.protocolo.Perfil;

/**
 * Mensagem ja decifrada com a Chave_Transito. Quando a decifragem falha, {@code conteudo} e
 * {@code null} e {@code ilegivel} e verdadeiro (a interface mostra "mensagem ilegivel").
 */
public record MensagemRecebida(
        String mid, Perfil remetente, String ts, MensagemChat.Tipo tipo, String conteudo, boolean ilegivel) {}
