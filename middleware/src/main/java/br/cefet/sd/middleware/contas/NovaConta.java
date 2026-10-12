package br.cefet.sd.middleware.contas;

import java.util.Objects;

/**
 * Dados validados de uma Conta a criar. A senha ja chega transformada pelo Tratamento_de_Senhas:
 * o repositorio nunca recebe a senha em texto legivel.
 *
 * @param login identificador unico, {@code ^[a-z0-9_]{3,20}$}
 * @param apelido nome exibido
 * @param senhaHash {@code sha256i$N$salt$hash}
 * @param avatar {@code av_01} a {@code av_12}
 * @param cor {@code #RRGGBB} em maiusculas
 */
public record NovaConta(String login, String apelido, String senhaHash, String avatar, String cor) {

    public NovaConta {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(apelido, "apelido");
        Objects.requireNonNull(senhaHash, "senhaHash");
        Objects.requireNonNull(avatar, "avatar");
        Objects.requireNonNull(cor, "cor");
    }

    /** A {@link Conta} correspondente, como o banco a devolveria. */
    public Conta paraConta() {
        return new Conta(login, apelido, senhaHash, avatar, cor);
    }

    /** Nao revela o hash da senha. */
    @Override
    public String toString() {
        return "NovaConta[login=" + login + ", apelido=" + apelido + ", avatar=" + avatar
                + ", cor=" + cor + ", senhaHash=***]";
    }
}
