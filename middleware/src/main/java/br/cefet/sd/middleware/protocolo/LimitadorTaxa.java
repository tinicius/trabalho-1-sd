package br.cefet.sd.middleware.protocolo;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Contador de janela deslizante para um {@link LimiteRequisicoes}. Uma instancia por conexao e por
 * operacao. Thread-safe. Requisicoes recusadas nao entram na contagem.
 */
public final class LimitadorTaxa {

    private final LimiteRequisicoes limite;
    private final ArrayDeque<Instant> aceitas = new ArrayDeque<>(); // guardado por this

    public LimitadorTaxa(LimiteRequisicoes limite) {
        this.limite = Objects.requireNonNull(limite, "limite");
    }

    /**
     * Tenta registrar uma requisicao no instante {@code agora}.
     *
     * @return {@code true} se ficou dentro do limite (e foi contada); {@code false} se excederia
     */
    public synchronized boolean tentar(Instant agora) {
        Instant inicio = agora.minus(limite.janela());
        // A janela e (agora - janela, agora]: o que esta exatamente na borda ja expirou.
        while (!aceitas.isEmpty() && !aceitas.peekFirst().isAfter(inicio)) {
            aceitas.pollFirst();
        }
        if (aceitas.size() >= limite.maximo()) {
            return false;
        }
        aceitas.addLast(agora);
        return true;
    }
}
