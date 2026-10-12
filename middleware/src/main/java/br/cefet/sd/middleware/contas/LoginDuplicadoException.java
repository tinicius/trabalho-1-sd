package br.cefet.sd.middleware.contas;

/**
 * Ja existe uma Conta com o Login informado. O Middleware a converte no codigo de erro
 * {@code LOGIN_DUPLICADO}. A mensagem e fixa e nao inclui o login.
 */
public class LoginDuplicadoException extends Exception {

    private static final long serialVersionUID = 1L;

    public LoginDuplicadoException() {
        super("Login duplicado");
    }

    public LoginDuplicadoException(Throwable causa) {
        super("Login duplicado", causa);
    }
}
