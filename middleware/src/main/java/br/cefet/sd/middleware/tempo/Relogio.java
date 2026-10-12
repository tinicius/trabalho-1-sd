package br.cefet.sd.middleware.tempo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Fonte de tempo do Middleware, apoiada em {@link Clock}.
 *
 * <p>E injetada no {@code ProcessadorProtocolo}, na {@code Limpeza} e na consulta de janela, o que
 * permite testar retencao sem esperar dias. Todos os
 * calculos de janela e retencao usam UTC.
 */
public final class Relogio {

    /** Janela_7_Dias. */
    public static final Duration JANELA_CONVERSAS = Duration.ofDays(7);

    private final Clock clock;

    private Relogio(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock").withZone(ZoneOffset.UTC);
    }

    /** Relogio do sistema, em UTC. */
    public static Relogio sistema() {
        return new Relogio(Clock.systemUTC());
    }

    /** Relogio apoiado no {@link Clock} informado (convertido para UTC). */
    public static Relogio de(Clock clock) {
        return new Relogio(clock);
    }

    /** Relogio parado em um instante, para testes. */
    public static Relogio fixo(Instant instante) {
        return new Relogio(Clock.fixed(instante, ZoneOffset.UTC));
    }

    /** O instante atual. */
    public Instant agora() {
        return clock.instant();
    }

    /** Inicio da Janela_7_Dias: {@code agora - 7 dias}. Registros com {@code ts} igual a ele ficam. */
    public Instant inicioJanelaConversas() {
        return agora().minus(JANELA_CONVERSAS);
    }

    /** O {@link Clock} subjacente (UTC). */
    public Clock clock() {
        return clock;
    }
}
