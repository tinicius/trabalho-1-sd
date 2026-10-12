package br.cefet.sd.middleware.sessao;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link Distribuidor} que guarda as Sessoes em um {@link CopyOnWriteArrayList}: muitas leituras
 * (cada difusao) e poucas escritas (login e logout).
 *
 * <p>A ordem entre quadros publicados e garantida pelo chamador (o {@code ordemLock} do
 * {@code ProcessadorProtocolo}): como {@link #publicar(String)} enfileira em cada Sessao na ordem
 * da chamada e cada fila e FIFO, todas as Sessoes veem a mesma ordem.
 */
public final class DistribuidorEmMemoria implements Distribuidor {

    private final CopyOnWriteArrayList<Sessao> sessoes = new CopyOnWriteArrayList<>();

    @Override
    public void registrar(Sessao sessao) {
        Objects.requireNonNull(sessao, "sessao");
        if (sessoes.addIfAbsent(sessao)) {
            // Se a Sessao ja estiver fechada, o observador dispara agora e a remove em seguida.
            sessao.aoFechar(motivo -> remover(sessao));
        }
    }

    @Override
    public void remover(Sessao sessao) {
        sessoes.remove(sessao);
    }

    @Override
    public void publicar(String quadroJson) {
        Objects.requireNonNull(quadroJson, "quadroJson");
        // A iteracao e sobre um instantaneo: remocoes durante a difusao (fila_cheia) sao seguras.
        for (Sessao sessao : sessoes) {
            try {
                sessao.enviar(quadroJson);
            } catch (RuntimeException e) {
                // enviar() nao deve lancar; se uma Sessao falhar, as demais continuam a receber.
                sessao.fechar("fechada_pelo_cliente");
            }
        }
    }

    @Override
    public List<Sessao> sessoes() {
        return List.copyOf(sessoes);
    }
}
