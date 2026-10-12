package br.cefet.sd.cliente.desktop.envio;

import br.cefet.sd.comum.emoticons.ConversorEmoticons;
import br.cefet.sd.comum.emoticons.TabelaConversao;
import br.cefet.sd.comum.protocolo.MensagemChat;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Controlador de envio do Cliente_Desktop (sem Swing). Mesmo comportamento de {@code
 * cliente-web/js/envio.js} e {@code cliente-python/comunicador/nucleo/envio.py}.
 *
 * <ul>
 *   <li>O botao Enviar e a tecla Enter chamam a MESMA funcao ({@link #acionarEnviar()}).
 *   <li>Texto vazio ou so de espacos em branco Unicode: nao envia e nao altera o campo.
 *   <li>Texto valido: converte emoticons, entrega a {@link EnviadorMensagens} (que cifra) e limpa o
 *       campo. Se o envio falhar, o texto e restaurado, a menos que o usuario ja tenha digitado
 *       outra coisa.
 *   <li>Figurinha: envia {@code tipo=figurinha} com {@code fig_NN}, sem tocar no campo.
 *   <li>O remetente nao grava no Historico_Local aqui: so ao receber o eco do Middleware.
 * </ul>
 */
public final class ControladorEnvio {
    /** Tamanho maximo do texto, em pontos de codigo (limite do Middleware). */
    public static final int MAXIMO_PONTOS_DE_CODIGO = 2000;

    private static final Pattern PADRAO_FIGURINHA = Pattern.compile("fig_[0-9]{2}");

    /** Motivos pelos quais um acionamento nao resultou em envio. */
    public enum MotivoNaoEnviado {
        EM_BRANCO,
        MUITO_LONGO,
        FIGURINHA_INVALIDA,
        FALHA
    }

    /**
     * Resultado de um acionamento. Quando {@code enviado}, {@code futuro} traz o desfecho final
     * (a falha chega depois, na thread de rede). Quando nao, {@code motivo} e preenchido e {@code
     * erro} traz a causa de {@code FALHA}.
     */
    public record ResultadoEnvio(
            boolean enviado,
            MotivoNaoEnviado motivo,
            CompletableFuture<?> futuro,
            Throwable erro) {
        static ResultadoEnvio naoEnviado(MotivoNaoEnviado motivo) {
            return new ResultadoEnvio(false, motivo, null, null);
        }

        static ResultadoEnvio falha(Throwable erro) {
            return new ResultadoEnvio(false, MotivoNaoEnviado.FALHA, null, erro);
        }

        static ResultadoEnvio aceito(CompletableFuture<?> futuro) {
            return new ResultadoEnvio(true, null, futuro, null);
        }
    }

    private final TabelaConversao tabela;
    private final EnviadorMensagens enviador;
    private final CampoDeTexto campo;
    private final Consumer<Runnable> despachar;

    /** Sem despacho: o texto e restaurado na thread que concluir o futuro. */
    public ControladorEnvio(TabelaConversao tabela, EnviadorMensagens enviador, CampoDeTexto campo) {
        this(tabela, enviador, campo, Runnable::run);
    }

    /**
     * @param despachar executa um {@code Runnable} na thread da interface; usado para restaurar o
     *     campo quando o futuro falha na thread de rede (na GUI, {@code SwingUtilities::invokeLater})
     */
    public ControladorEnvio(
            TabelaConversao tabela,
            EnviadorMensagens enviador,
            CampoDeTexto campo,
            Consumer<Runnable> despachar) {
        if (tabela == null || enviador == null || campo == null || despachar == null) {
            throw new IllegalArgumentException("tabela, enviador, campo e despachar sao obrigatorios");
        }
        this.tabela = tabela;
        this.enviador = enviador;
        this.campo = campo;
        this.despachar = despachar;
    }

    // ----- funcoes puras ------------------------------------------------------

    /**
     * {@code true} se {@code texto} e vazio ou so de espacos em branco Unicode (propriedade
     * White_Space, a mesma usada na conversao de emoticons).
     */
    public static boolean ehEmBranco(String texto) {
        if (texto == null) {
            throw new IllegalArgumentException("O texto e obrigatorio");
        }
        for (int i = 0; i < texto.length(); i++) {
            if (!ehEspaco(texto.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Quantidade de pontos de codigo Unicode de {@code texto}. */
    public static int contarPontosDeCodigo(String texto) {
        return texto.codePointCount(0, texto.length());
    }

    /** {@code true} se {@code id} tem o formato {@code fig_NN}. */
    public static boolean ehIdFigurinha(String id) {
        return id != null && PADRAO_FIGURINHA.matcher(id).matches();
    }

    /** Unicode White_Space: U+0009-000D, 0020, 0085, 00A0, 1680, 2000-200A, 2028, 2029, 202F, 205F, 3000. */
    static boolean ehEspaco(char c) {
        return (c >= 0x0009 && c <= 0x000D)
                || c == 0x0020
                || c == 0x0085
                || c == 0x00A0
                || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028
                || c == 0x2029
                || c == 0x202F
                || c == 0x205F
                || c == 0x3000;
    }

    // ----- acoes --------------------------------------------------------------

    /** Acao unica do botao Enviar e da tecla Enter. */
    public ResultadoEnvio acionarEnviar() {
        String original = campo.ler();
        if (ehEmBranco(original)) {
            return ResultadoEnvio.naoEnviado(MotivoNaoEnviado.EM_BRANCO);
        }
        String conteudo = ConversorEmoticons.converter(original, tabela);
        if (contarPontosDeCodigo(conteudo) > MAXIMO_PONTOS_DE_CODIGO) {
            // Mantem o campo intacto para o usuario encurtar o texto.
            return ResultadoEnvio.naoEnviado(MotivoNaoEnviado.MUITO_LONGO);
        }
        // Limpa antes de aguardar a resposta: um segundo Enter nao reenvia o mesmo texto.
        campo.escrever("");
        CompletableFuture<?> futuro;
        try {
            futuro = enviador.enviar(MensagemChat.Tipo.TEXTO, conteudo);
        } catch (RuntimeException erro) {
            restaurar(original);
            return ResultadoEnvio.falha(erro);
        }
        futuro.whenComplete(
                (resposta, erro) -> {
                    if (erro != null) {
                        despachar.accept(() -> restaurar(original));
                    }
                });
        return ResultadoEnvio.aceito(futuro);
    }

    /**
     * Manipulador de teclado do campo: Enter (fora de composicao IME) aciona o mesmo envio do botao.
     * Devolve {@code null} se a tecla nao e tratada.
     */
    public ResultadoEnvio aoTeclar(boolean teclaEnter, boolean compondo) {
        if (!teclaEnter || compondo) {
            return null;
        }
        return acionarEnviar();
    }

    /** Envia uma figurinha pelo identificador ({@code fig_NN}), sem alterar o campo. */
    public ResultadoEnvio enviarFigurinha(String id) {
        if (!ehIdFigurinha(id)) {
            return ResultadoEnvio.naoEnviado(MotivoNaoEnviado.FIGURINHA_INVALIDA);
        }
        try {
            return ResultadoEnvio.aceito(enviador.enviar(MensagemChat.Tipo.FIGURINHA, id));
        } catch (RuntimeException erro) {
            return ResultadoEnvio.falha(erro);
        }
    }

    /**
     * Insere {@code emoji} no campo na posicao do cursor (indice UTF-16) e devolve a nova posicao
     * do cursor. Independe da conversao de emoticons.
     */
    public int inserirEmojiNoCampo(String emoji, int posicao) {
        InsercaoEmoji.Resultado r = InsercaoEmoji.inserirComCursor(campo.ler(), posicao, emoji);
        campo.escrever(r.texto());
        return r.posicao();
    }

    private void restaurar(String original) {
        if (campo.ler().isEmpty()) {
            campo.escrever(original);
        }
    }
}
