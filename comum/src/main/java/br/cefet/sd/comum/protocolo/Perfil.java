package br.cefet.sd.comum.protocolo;

import java.util.Objects;

/**
 * Perfil publico de um usuario, como trafega em {@code perfil} (login) e em {@code remetente}
 * (message e history). Nunca contem senha nem hash.
 *
 * @param login identificador unico do usuario
 * @param apelido nome exibido
 * @param avatar identificador do avatar (por exemplo {@code av_03})
 * @param cor cor do apelido, no formato {@code #RRGGBB}
 */
public record Perfil(String login, String apelido, String avatar, String cor) {

    public Perfil {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(apelido, "apelido");
        Objects.requireNonNull(avatar, "avatar");
        Objects.requireNonNull(cor, "cor");
    }
}
