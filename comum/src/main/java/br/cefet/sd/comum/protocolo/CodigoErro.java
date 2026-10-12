package br.cefet.sd.comum.protocolo;

import java.util.Optional;

/**
 * Codigos de erro do Protocolo.
 *
 * <p>Cada codigo tem um texto fixo em {@link #mensagem()}, que vai em {@code erro.mensagem}. O texto
 * nunca ecoa dados recebidos. Os textos sao identicos aos de {@code protocolo.py} (Cliente_Terceiro).
 */
public enum CodigoErro {
    FORMATO_INVALIDO("Formato de mensagem invalido"),
    MENSAGEM_MUITO_GRANDE("Mensagem acima do tamanho maximo"),
    VERSAO_NAO_SUPORTADA("Versao do protocolo nao suportada"),
    OPERACAO_DESCONHECIDA("Operacao desconhecida"),
    NAO_AUTENTICADO("Autenticacao necessaria"),
    JA_AUTENTICADO("Sessao ja autenticada"),
    CREDENCIAIS_INVALIDAS("Login ou senha invalidos"),
    LOGIN_DUPLICADO("Login ja cadastrado"),
    CAMPO_AUSENTE("Campo obrigatorio ausente"),
    CAMPO_INVALIDO("Campo com valor invalido"),
    DECIFRAGEM_FALHOU("Nao foi possivel decifrar os dados"),
    CONTEUDO_INVALIDO("Conteudo invalido"),
    LIMITE_EXCEDIDO("Limite de requisicoes excedido"),
    SERVICO_INDISPONIVEL("Servico indisponivel"),
    ERRO_INTERNO("Erro interno");

    private final String mensagem;

    CodigoErro(String mensagem) {
        this.mensagem = mensagem;
    }

    /** Texto fixo de {@code erro.mensagem} para este codigo. */
    public String mensagem() {
        return mensagem;
    }

    /** Procura o codigo pelo nome exato (o valor de {@code erro.codigo}); vazio se desconhecido. */
    public static Optional<CodigoErro> deNome(String nome) {
        if (nome == null) {
            return Optional.empty();
        }
        for (CodigoErro c : values()) {
            if (c.name().equals(nome)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
