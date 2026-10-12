package br.cefet.sd.middleware.logs;

import java.time.Instant;
import java.util.Objects;

/**
 * Registro do Log_Acessos. Campos opcionais sao {@code null} quando
 * nao se aplicam.
 *
 * <p>Em {@code LOGIN_RECUSADO}, {@code login} so deve ser preenchido se o valor tentado casar com
 * {@code ^[a-z0-9_]{3,20}$}; caso contrario fica {@code null}, para nao gravar uma senha digitada
 * por engano no campo de login.
 *
 * @param ts data/hora do servidor
 * @param evento tipo do evento
 * @param login login envolvido, se conhecido e seguro
 * @param sessao id da Sessao, se ja existe
 * @param dispositivo UUID do dispositivo, se informado
 * @param cliente {@code desktop}, {@code web} ou {@code python}, se informado
 * @param endereco endereco remoto da conexao
 * @param motivo motivo da desconexao ({@code logout}, {@code fechada_pelo_cliente},
 *     {@code timeout}, {@code fila_cheia}, {@code falhas_de_login}); so em {@code DESCONEXAO}
 */
public record EventoAcesso(
        Instant ts,
        Evento evento,
        String login,
        String sessao,
        String dispositivo,
        String cliente,
        String endereco,
        String motivo) {

    /** Valor de {@code evento}. */
    public enum Evento {
        CONEXAO("conexao"),
        LOGIN_OK("login_ok"),
        LOGIN_RECUSADO("login_recusado"),
        DESCONEXAO("desconexao");

        private final String valor;

        Evento(String valor) {
            this.valor = valor;
        }

        public String valor() {
            return valor;
        }
    }

    public EventoAcesso {
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(evento, "evento");
    }
}
