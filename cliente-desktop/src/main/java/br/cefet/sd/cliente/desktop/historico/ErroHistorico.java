package br.cefet.sd.cliente.desktop.historico;

/**
 * Erro de uso ou de armazenamento do Historico_Local. A mensagem nunca inclui chaves nem conteudo
 * de mensagens.
 */
public final class ErroHistorico extends Exception {

    private static final long serialVersionUID = 1L;

    public ErroHistorico(String mensagem) {
        super(mensagem);
    }

    public ErroHistorico(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
