package br.cefet.sd.middleware.sessao;

import java.util.List;

/** Conjunto de Sessoes conectadas, ao qual o Middleware difunde as Mensagens. */
public interface Distribuidor {

    /**
     * Passa a distribuir quadros a esta Sessao. Registrar duas vezes a mesma Sessao nao a duplica.
     * Quando a Sessao se encerra, ela e removida automaticamente.
     */
    void registrar(Sessao sessao);

    /** Deixa de distribuir quadros a esta Sessao. Idempotente. */
    void remover(Sessao sessao);

    /**
     * Enfileira o quadro em todas as Sessoes registradas, inclusive a do remetente. Somente
     * enfileira: nao espera nenhuma escrita em rede.
     */
    void publicar(String quadroJson);

    /** Copia das Sessoes registradas no momento da chamada. */
    List<Sessao> sessoes();
}
