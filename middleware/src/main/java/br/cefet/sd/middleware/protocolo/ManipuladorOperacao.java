package br.cefet.sd.middleware.protocolo;

/**
 * Logica de uma operacao do Protocolo ({@code register}, {@code login}, {@code send_message}...),
 * registrada em {@link ProcessadorProtocolo#registrar}.
 *
 * <p>Quando chamado, o envelope ja foi validado (campos {@code v}, {@code op} e {@code id}), a
 * exigencia de Sessao autenticada ja foi aplicada e o limite de requisicoes ja foi conferido. O
 * manipulador valida os campos proprios da operacao e responde por {@link Requisicao}. Excecoes
 * inesperadas podem simplesmente propagar: o processador as converte em {@code ERRO_INTERNO} e as
 * registra no Log_Erros. Responda por ultimo, para que uma excecao nao gere duas respostas.
 */
@FunctionalInterface
public interface ManipuladorOperacao {

    void tratar(Requisicao requisicao) throws Exception;
}
