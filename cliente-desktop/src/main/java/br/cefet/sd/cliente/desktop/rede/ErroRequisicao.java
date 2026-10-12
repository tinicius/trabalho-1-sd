package br.cefet.sd.cliente.desktop.rede;

/**
 * Erro de uma requisicao: resposta {@code ok:false}, falha de rede ou tempo esgotado.
 *
 * <p>{@link #codigo()} e um codigo do Protocolo ({@code CodigoErro}) ou um dos codigos locais
 * {@link #CONEXAO_PERDIDA}, {@link #TEMPO_ESGOTADO}, {@link #SEM_CONEXAO}. A mensagem nunca inclui
 * dados do usuario, senhas ou chaves.
 */
public final class ErroRequisicao extends Exception {

    private static final long serialVersionUID = 1L;

    public static final String CONEXAO_PERDIDA = "CONEXAO_PERDIDA";
    public static final String TEMPO_ESGOTADO = "TEMPO_ESGOTADO";
    public static final String SEM_CONEXAO = "SEM_CONEXAO";

    private final String codigo;
    private final String campo;

    public ErroRequisicao(String codigo, String mensagem) {
        this(codigo, mensagem, null);
    }

    public ErroRequisicao(String codigo, String mensagem, String campo) {
        super(mensagem);
        this.codigo = codigo;
        this.campo = campo;
    }

    public String codigo() {
        return codigo;
    }

    /** Campo apontado por {@code erro.campo}, ou {@code null}. */
    public String campo() {
        return campo;
    }
}
