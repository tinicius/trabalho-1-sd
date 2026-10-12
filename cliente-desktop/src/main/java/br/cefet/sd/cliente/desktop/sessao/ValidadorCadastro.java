package br.cefet.sd.cliente.desktop.sessao;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validacao local dos formularios de login e cadastro (sem Swing). Espelha as regras do Middleware,
 * so para dar resposta imediata: o Middleware continua sendo a
 * autoridade e repete a validacao.
 */
public final class ValidadorCadastro {

    private static final Pattern LOGIN = Pattern.compile("[a-z0-9_]{3,20}");
    private static final Pattern AVATAR = Pattern.compile("av_(0[1-9]|1[0-2])");
    private static final Pattern COR = Pattern.compile("#[0-9A-Fa-f]{6}");

    public static final int SENHA_MINIMA = 6;
    public static final int SENHA_MAXIMA = 72;
    public static final int APELIDO_MAXIMO = 30;

    private ValidadorCadastro() {}

    /** Campo com problema (nome do campo no Protocolo, ou {@code confirmacao}) e texto ao usuario. */
    public record ErroValidacao(String campo, String mensagem) {}

    /** Login e senha nao vazios (a senha nao e alterada, nem aparada). */
    public static Optional<ErroValidacao> validarLogin(String login, String senha) {
        if (login == null || login.isBlank()) {
            return erro("login", "Informe o login.");
        }
        if (senha == null || senha.isEmpty()) {
            return erro("senha", "Informe a senha.");
        }
        return Optional.empty();
    }

    /** Valida o cadastro na ordem fixa login, apelido, senha, confirmacao, avatar, cor. */
    public static Optional<ErroValidacao> validarCadastro(DadosCadastro dados) {
        if (dados == null) {
            return erro("login", "Preencha o formulário de cadastro.");
        }
        String login = dados.login() == null ? "" : dados.login().strip();
        if (login.isEmpty()) {
            return erro("login", "Informe o login.");
        }
        if (!LOGIN.matcher(login).matches()) {
            return erro("login", "O login deve ter de 3 a 20 caracteres: letras minúsculas, números ou _.");
        }
        String apelido = dados.apelido() == null ? "" : dados.apelido().strip();
        if (apelido.isEmpty()) {
            return erro("apelido", "Informe o apelido.");
        }
        if (apelido.codePointCount(0, apelido.length()) > APELIDO_MAXIMO) {
            return erro("apelido", "O apelido pode ter no máximo " + APELIDO_MAXIMO + " caracteres.");
        }
        String senha = dados.senha() == null ? "" : dados.senha();
        if (senha.isEmpty()) {
            return erro("senha", "Informe a senha.");
        }
        int tamanho = senha.codePointCount(0, senha.length());
        if (tamanho < SENHA_MINIMA || tamanho > SENHA_MAXIMA) {
            return erro("senha", "A senha deve ter de " + SENHA_MINIMA + " a " + SENHA_MAXIMA + " caracteres.");
        }
        if (!senha.equals(dados.confirmacao())) {
            return erro("confirmacao", "A confirmação da senha não confere.");
        }
        if (dados.avatar() == null || !AVATAR.matcher(dados.avatar()).matches()) {
            return erro("avatar", "Escolha um avatar.");
        }
        if (dados.cor() == null || !COR.matcher(dados.cor()).matches()) {
            return erro("cor", "Escolha uma cor para o apelido.");
        }
        return Optional.empty();
    }

    private static Optional<ErroValidacao> erro(String campo, String mensagem) {
        return Optional.of(new ErroValidacao(campo, mensagem));
    }
}
