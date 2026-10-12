package br.cefet.sd.middleware.logs;

import java.io.IOException;
import java.time.Instant;

/**
 * Porta do Log_Envios ({@code dados/logs/envios.txt}). Cada registro tem exatamente {@code ts} e
 * {@code login}: sem mid, tipo, texto, figurinha, emoji nem conteudo cifrado.
 */
public interface LogEnvios {

    /**
     * Registra que {@code login} enviou uma Mensagem em {@code ts}.
     *
     * @throws IOException se a gravacao falha
     */
    void registrar(Instant ts, String login) throws IOException;
}
