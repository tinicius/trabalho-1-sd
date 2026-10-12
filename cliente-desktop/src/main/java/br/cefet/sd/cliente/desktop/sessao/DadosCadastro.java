package br.cefet.sd.cliente.desktop.sessao;

/**
 * Campos do formulario de cadastro, como digitados. {@code confirmacao} e a repeticao da senha
 * (conferida so no cliente). {@code avatar} e {@code av_NN}; {@code cor} e {@code #RRGGBB}.
 */
public record DadosCadastro(
        String login, String apelido, String senha, String confirmacao, String avatar, String cor) {

    /** Nunca expoe a senha. */
    @Override
    public String toString() {
        return "DadosCadastro[login=" + login + ", apelido=" + apelido + ", avatar=" + avatar
                + ", cor=" + cor + ", senha=***]";
    }
}
