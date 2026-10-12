package br.cefet.sd.middleware.logs;

import java.time.Instant;
import java.util.Objects;

/**
 * Registro do Log_Erros. {@code sessao}, {@code dispositivo},
 * {@code cliente} e {@code evid} sao {@code null} quando nao disponiveis.
 *
 * <p>O mascaramento de {@code descricao} e {@code pilha} e feito pela
 * implementacao de {@link LogErros} antes de gravar.
 *
 * @param ts data/hora do evento (do Cliente quando a origem e cliente)
 * @param origem middleware ou cliente
 * @param modulo modulo de origem
 * @param tipo tipo da excecao
 * @param descricao descricao (sem senha nem conteudo de conversa)
 * @param pilha rastreamento da pilha
 * @param sessao id da Sessao, se houver
 * @param dispositivo UUID do dispositivo, se houver
 * @param cliente {@code desktop}, {@code web} ou {@code python}, se conhecido
 * @param evid id do evento no Buffer_Offline_Erros, se veio de um Cliente
 * @param descartados eventos descartados pelo Buffer_Offline_Erros desde o ultimo envio
 */
public record EventoErro(
        Instant ts,
        Origem origem,
        String modulo,
        String tipo,
        String descricao,
        String pilha,
        String sessao,
        String dispositivo,
        String cliente,
        String evid,
        int descartados) {

    /** Valor de {@code origem}. */
    public enum Origem {
        MIDDLEWARE("middleware"),
        CLIENTE("cliente");

        private final String valor;

        Origem(String valor) {
            this.valor = valor;
        }

        public String valor() {
            return valor;
        }
    }

    public EventoErro {
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(origem, "origem");
        Objects.requireNonNull(modulo, "modulo");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(descricao, "descricao");
        Objects.requireNonNull(pilha, "pilha");
    }

    /** Evento do proprio Middleware, a partir de uma excecao. */
    public static EventoErro doMiddleware(Instant ts, String modulo, Throwable erro, String sessao) {
        java.io.StringWriter pilha = new java.io.StringWriter();
        erro.printStackTrace(new java.io.PrintWriter(pilha));
        return new EventoErro(
                ts,
                Origem.MIDDLEWARE,
                modulo,
                erro.getClass().getSimpleName(),
                String.valueOf(erro.getMessage()),
                pilha.toString(),
                sessao,
                null,
                null,
                null,
                0);
    }
}
