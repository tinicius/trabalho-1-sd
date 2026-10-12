package br.cefet.sd.middleware.protocolo;

import java.time.Duration;
import java.util.Objects;

/**
 * Limite de requisicoes de uma operacao, por conexao: no maximo {@code maximo} requisicoes aceitas em
 * qualquer intervalo de duracao {@code janela}. Acima disso o Middleware responde
 * {@code LIMITE_EXCEDIDO}.
 *
 * @param maximo quantidade maxima de requisicoes na janela (maior que zero)
 * @param janela duracao da janela deslizante (maior que zero)
 */
public record LimiteRequisicoes(int maximo, Duration janela) {

    /** {@code send_message}: 30 envios por 10 s. */
    public static final LimiteRequisicoes SEND_MESSAGE = new LimiteRequisicoes(30, Duration.ofSeconds(10));

    /** {@code error_report}: 10 requisicoes por minuto. */
    public static final LimiteRequisicoes ERROR_REPORT = new LimiteRequisicoes(10, Duration.ofMinutes(1));

    public LimiteRequisicoes {
        Objects.requireNonNull(janela, "janela");
        if (maximo < 1) {
            throw new IllegalArgumentException("maximo deve ser positivo: " + maximo);
        }
        if (janela.isZero() || janela.isNegative()) {
            throw new IllegalArgumentException("janela deve ser positiva: " + janela);
        }
    }
}
