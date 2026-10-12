package br.cefet.sd.middleware.sessao;

import br.cefet.sd.comum.protocolo.Perfil;
import br.cefet.sd.comum.protocolo.TipoTransporte;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Uma conexao de Cliente com o Middleware, vinculada (apos o login) a identidade do usuario.
 *
 * <p>O remetente de uma Mensagem sempre vem desta identidade, nunca do payload. Uma Sessao e
 * criada na conexao, ainda nao autenticada, e vira autenticada no login. O mesmo login pode ter
 * varias Sessoes simultaneas.
 */
public interface Sessao {

    /** Id da Sessao (16 bytes aleatorios em hexadecimal), gerado no login; {@code null} antes disso. */
    String id();

    /** Perfil publico do usuario; vazio ate autenticar. */
    Optional<Perfil> perfil();

    /** {@code true} depois do login com sucesso. */
    boolean autenticada();

    /** Transporte da conexao: {@code TCP} ou {@code WEBSOCKET}. */
    TipoTransporte transporte();

    /** Endereco remoto da conexao, para o Log_Acessos; pode ser {@code null}. */
    String endereco();

    /** UUID do dispositivo informado no login; {@code null} antes de autenticar ou se ausente. */
    String dispositivo();

    /** Tipo do Cliente informado no login ({@code desktop}, {@code web}, {@code python}); pode ser {@code null}. */
    String cliente();

    /**
     * Vincula o perfil a esta Sessao e gera o id da Sessao.
     *
     * @param perfil perfil publico do usuario autenticado
     * @param dispositivo UUID do dispositivo, ou {@code null}
     * @param cliente tipo do Cliente, ou {@code null}
     * @return o id gerado (16 bytes em hexadecimal)
     * @throws IllegalStateException se a Sessao ja esta autenticada
     */
    String autenticar(Perfil perfil, String dispositivo, String cliente);

    /**
     * Enfileira um quadro JSON para envio ao Cliente. Nunca bloqueia e nunca lanca excecao. Se a fila
     * de saida estourar, a Sessao e encerrada com o motivo {@code fila_cheia}. Quadros enviados a uma
     * Sessao fechada sao descartados.
     */
    void enviar(String json);

    /**
     * Encerra a Sessao imediatamente: descarta o que estiver na fila, fecha a conexao e avisa os
     * observadores. Idempotente; so o primeiro motivo vale.
     *
     * @param motivo {@code logout}, {@code fechada_pelo_cliente}, {@code timeout}, {@code fila_cheia}
     *     ou {@code falhas_de_login}
     */
    void fechar(String motivo);

    /**
     * Encerra a Sessao depois de entregar o que ja esta na fila (por exemplo a resposta do
     * {@code logout}). Por padrao equivale a {@link #fechar(String)}.
     */
    default void fecharAposEsvaziar(String motivo) {
        fechar(motivo);
    }

    /** {@code true} depois que a Sessao foi (ou esta sendo) encerrada. */
    boolean fechada();

    /**
     * Registra um observador chamado uma unica vez com o motivo do encerramento. Se a Sessao ja
     * estiver encerrada, o observador e chamado de imediato.
     */
    void aoFechar(Consumer<String> observador);
}
