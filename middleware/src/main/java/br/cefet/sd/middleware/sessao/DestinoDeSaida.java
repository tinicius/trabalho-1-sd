package br.cefet.sd.middleware.sessao;

import java.io.IOException;

/**
 * Lado de escrita do transporte de uma Sessao. O listener TCP escreve o quadro seguido de
 * {@code \n}; o listener WebSocket envia um frame de texto.
 *
 * <p>{@link #escrever(String)} e chamado somente pela thread escritora da Sessao, uma por vez.
 */
public interface DestinoDeSaida {

    /** Escreve um quadro JSON no transporte. Pode bloquear se o Cliente for lento. */
    void escrever(String json) throws IOException;

    /** Fecha o transporte. Deve ser idempotente e nao lancar excecao. */
    void fechar();
}
