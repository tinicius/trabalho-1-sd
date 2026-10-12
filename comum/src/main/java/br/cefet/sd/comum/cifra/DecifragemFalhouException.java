package br.cefet.sd.comum.cifra;

/**
 * Lancada quando a decifragem da Cifra_Manual e recusada: Base64 invalido, tamanho ou versao
 * invalidos, MAC que nao confere (chave errada ou adulteracao) ou bytes que nao formam UTF-8
 * valido.
 *
 * <p>A mensagem e um texto fixo por causa e nunca contem dados recebidos nem material de chave.
 */
public final class DecifragemFalhouException extends Exception {

    private static final long serialVersionUID = 1L;

    public DecifragemFalhouException(String mensagem) {
        super(mensagem);
    }

    public DecifragemFalhouException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
