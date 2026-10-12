package br.cefet.sd.middleware.contas;

/**
 * O Banco_de_Dados esta inacessivel (conexao ou consulta falhou por motivo que nao e regra de
 * negocio). O Middleware a converte no codigo de erro {@code SERVICO_INDISPONIVEL}.
 */
public class ServicoIndisponivelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServicoIndisponivelException(Throwable causa) {
        super("Banco de dados indisponivel", causa);
    }
}
