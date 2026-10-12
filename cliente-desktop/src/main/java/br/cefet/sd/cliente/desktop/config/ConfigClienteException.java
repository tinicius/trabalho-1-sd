package br.cefet.sd.cliente.desktop.config;

/**
 * Falha de configuracao do Cliente_Desktop que impede o login. A mensagem orienta o usuario sobre
 * como corrigir o problema e nunca contem o material de uma chave.
 */
public final class ConfigClienteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigClienteException(String mensagem) {
        super(mensagem);
    }
}
