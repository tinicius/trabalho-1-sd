package br.cefet.sd.middleware.contas;

import br.cefet.sd.comum.protocolo.Perfil;
import java.util.Objects;

/**
 * Conta persistida no Banco_de_Dados (tabela {@code contas}).
 *
 * @param login identificador unico, {@code ^[a-z0-9_]{3,20}$}
 * @param apelido nome exibido
 * @param senhaHash valor armazenado do Tratamento_de_Senhas ({@code sha256i$N$salt$hash})
 * @param avatar {@code av_01} a {@code av_12}
 * @param cor {@code #RRGGBB} em maiusculas
 */
public record Conta(String login, String apelido, String senhaHash, String avatar, String cor) {

    public Conta {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(apelido, "apelido");
        Objects.requireNonNull(senhaHash, "senhaHash");
        Objects.requireNonNull(avatar, "avatar");
        Objects.requireNonNull(cor, "cor");
    }

    /** Perfil publico da conta: nunca inclui o hash da senha. */
    public Perfil perfil() {
        return new Perfil(login, apelido, avatar, cor);
    }

    /** Nao revela o hash da senha. */
    @Override
    public String toString() {
        return "Conta[login=" + login + ", apelido=" + apelido + ", avatar=" + avatar
                + ", cor=" + cor + ", senhaHash=***]";
    }
}
