package br.cefet.sd.middleware.config;

import java.util.List;

/**
 * A configuracao do Middleware e invalida e a partida deve ser recusada.
 *
 * <p>As mensagens citam apenas o nome da propriedade e a regra violada. Valores de chave, senha
 * ou qualquer outro dado recebido nunca sao incluidos.
 */
public final class ConfiguracaoInvalidaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> problemas;

    public ConfiguracaoInvalidaException(List<String> problemas) {
        super("Configuracao invalida: " + String.join("; ", problemas));
        this.problemas = List.copyOf(problemas);
    }

    /** Cada problema encontrado, na ordem de verificacao. */
    public List<String> problemas() {
        return problemas;
    }
}
