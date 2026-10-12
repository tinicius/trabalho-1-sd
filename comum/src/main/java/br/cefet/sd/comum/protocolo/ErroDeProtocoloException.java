package br.cefet.sd.comum.protocolo;

import java.util.Objects;

/**
 * Quadro recebido fora do Protocolo. {@link #codigo()} e o codigo que o Middleware devolveria para o
 * mesmo quadro; {@link #campo()} identifica o campo problematico quando se conhece (pode ser nulo).
 *
 * <p>A mensagem e o texto fixo do codigo e nunca contem dados recebidos.
 */
public final class ErroDeProtocoloException extends Exception {

    private static final long serialVersionUID = 1L;

    private final CodigoErro codigo;
    private final String campo;

    public ErroDeProtocoloException(CodigoErro codigo) {
        this(codigo, null);
    }

    public ErroDeProtocoloException(CodigoErro codigo, String campo) {
        super(Objects.requireNonNull(codigo, "codigo").mensagem());
        this.codigo = codigo;
        this.campo = campo;
    }

    public CodigoErro codigo() {
        return codigo;
    }

    public String campo() {
        return campo;
    }
}
