package br.cefet.sd.cliente.desktop.erros;

/**
 * Evento de erro retido no Buffer_Offline_Erros.
 *
 * <p>{@code descricao} e {@code pilha} ja estao mascaradas e truncadas. {@code tentativas} conta os
 * envios que chegaram ao Middleware (ou falharam por motivo diferente de falta de conexao).
 */
public record EventoPendente(
        String evid,
        String ts,
        String modulo,
        String tipo,
        String descricao,
        String pilha,
        String dispositivo,
        String cliente,
        int tentativas) {

    public EventoPendente comTentativas(int novas) {
        return new EventoPendente(evid, ts, modulo, tipo, descricao, pilha, dispositivo, cliente, novas);
    }
}
