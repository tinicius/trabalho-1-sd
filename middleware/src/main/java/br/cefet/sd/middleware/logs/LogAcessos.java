package br.cefet.sd.middleware.logs;

import java.io.IOException;

/** Porta do Log_Acessos ({@code dados/logs/acessos.txt}), um objeto JSON por linha. */
public interface LogAcessos {

    /**
     * Grava o evento de conexao, autenticacao, recusa ou desconexao. O registro nunca
     * contem senha.
     *
     * @throws IOException se a gravacao falha
     */
    void registrar(EventoAcesso evento) throws IOException;
}
