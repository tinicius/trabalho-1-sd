package br.cefet.sd.cliente.desktop.sessao;

import br.cefet.sd.cliente.desktop.envio.EnviadorMensagens;

/** O que a tela da sala pede ao {@link ControladorSessao}. */
public interface AcoesSala {

    /** Funcao de envio injetada no {@code ControladorEnvio} da sala. */
    EnviadorMensagens enviador();

    /** Retoma a reconexao no estado "desconectado"; se nao ha o que retomar, volta ao login. */
    void tentarNovamente();

    /** Logout: encerra a sessao e volta ao login. */
    void sair();
}
