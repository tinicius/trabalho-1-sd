package br.cefet.sd.middleware.logs;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Registro do Log_Envios: exatamente {@code ts} e {@code login}. {@code ts} e
 * truncado para milissegundos na construcao, de modo que o registro e igual ao que se le de volta
 * do arquivo.
 *
 * @param ts data/hora do envio
 * @param login login do remetente autenticado
 */
public record RegistroEnvio(Instant ts, String login) {
    public RegistroEnvio {
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(login, "login");
        ts = ts.truncatedTo(ChronoUnit.MILLIS);
    }
}
