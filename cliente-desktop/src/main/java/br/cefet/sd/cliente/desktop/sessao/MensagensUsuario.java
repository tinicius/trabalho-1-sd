package br.cefet.sd.cliente.desktop.sessao;

import br.cefet.sd.cliente.desktop.rede.ErroRequisicao;
import br.cefet.sd.cliente.desktop.rede.EstadoConexao;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Textos amigaveis ao usuario (sem Swing): erros por codigo do Protocolo e estado da conexao.
 * Nenhum texto repete dados recebidos, senhas ou chaves.
 */
public final class MensagensUsuario {

    /** Texto para qualquer falha que nao seja uma {@link ErroRequisicao}. */
    public static final String ERRO_GENERICO = "Ocorreu um erro inesperado. Tente novamente.";

    private MensagensUsuario() {}

    /** Mensagem amigavel para uma falha de requisicao (desembrulha {@code CompletionException}). */
    public static String paraErro(Throwable erro) {
        Throwable atual = erro;
        while ((atual instanceof CompletionException || atual instanceof ExecutionException)
                && atual.getCause() != null) {
            atual = atual.getCause();
        }
        if (atual instanceof ErroRequisicao e) {
            return paraCodigo(e.codigo(), e.campo());
        }
        return ERRO_GENERICO;
    }

    /** Mensagem amigavel para um codigo do Protocolo (ou codigo local de {@link ErroRequisicao}). */
    public static String paraCodigo(String codigo, String campo) {
        if (codigo == null) {
            return ERRO_GENERICO;
        }
        return switch (codigo) {
            case "CREDENCIAIS_INVALIDAS" -> "Login ou senha incorretos.";
            case "LOGIN_DUPLICADO" -> "Este login já está em uso. Escolha outro.";
            case "CAMPO_AUSENTE" -> campo == null
                    ? "Preencha todos os campos."
                    : "Preencha o campo " + nomeCampo(campo) + ".";
            case "CAMPO_INVALIDO" -> campo == null
                    ? "Algum campo tem um valor inválido."
                    : "O valor informado em " + nomeCampo(campo) + " é inválido.";
            case ErroRequisicao.SEM_CONEXAO, ErroRequisicao.CONEXAO_PERDIDA ->
                    "Não foi possível falar com o servidor. Verifique se o Middleware está em execução e tente de novo.";
            case ErroRequisicao.TEMPO_ESGOTADO -> "O servidor demorou demais para responder. Tente de novo.";
            case "SERVICO_INDISPONIVEL" ->
                    "O servidor está temporariamente indisponível. Tente de novo em instantes.";
            case "DECIFRAGEM_FALHOU" ->
                    "A chave de trânsito deste aplicativo não confere com a do servidor. "
                            + "Peça uma nova cópia ao administrador.";
            case "LIMITE_EXCEDIDO" -> "Muitas requisições em pouco tempo. Aguarde alguns segundos.";
            case "JA_AUTENTICADO" -> "Esta conexão já está autenticada.";
            case "NAO_AUTENTICADO" -> "Sua sessão não está autenticada. Entre novamente.";
            case "CONTEUDO_INVALIDO" ->
                    "Conteúdo inválido: a mensagem está vazia ou passa de 2000 caracteres.";
            case "VERSAO_NAO_SUPORTADA" -> "A versão do protocolo deste aplicativo não é aceita pelo servidor.";
            default -> ERRO_GENERICO;
        };
    }

    /** Nome do campo do Protocolo como o usuario o conhece. */
    public static String nomeCampo(String campo) {
        if (campo == null) {
            return "informado";
        }
        return switch (campo) {
            case "login" -> "login";
            case "apelido" -> "apelido";
            case "senha" -> "senha";
            case "confirmacao" -> "confirmação da senha";
            case "avatar" -> "avatar";
            case "cor" -> "cor";
            default -> "informado";
        };
    }

    /** Texto do indicador de estado da tela da sala. */
    public static String paraEstado(EstadoConexao estado, Map<String, Object> detalhe) {
        if (estado == null) {
            return "";
        }
        return switch (estado) {
            case AUTENTICADO -> "Conectado";
            case CONECTANDO -> "Conectando…";
            case CONECTADO -> "Conectado, autenticando…";
            case RECONECTANDO -> reconectando(detalhe);
            case DESCONECTADO -> desconectado(detalhe);
        };
    }

    private static String reconectando(Map<String, Object> detalhe) {
        Object tentativa = detalhe == null ? null : detalhe.get("tentativa");
        Object maximo = detalhe == null ? null : detalhe.get("maximo");
        if (tentativa instanceof Number t && maximo instanceof Number m) {
            return "Reconectando… (tentativa " + t.intValue() + " de " + m.intValue() + ")";
        }
        return "Reconectando…";
    }

    private static String desconectado(Map<String, Object> detalhe) {
        Object motivo = detalhe == null ? null : detalhe.get("motivo");
        if ("tentativas_esgotadas".equals(motivo)) {
            return "Desconectado: não foi possível reconectar após várias tentativas";
        }
        if ("credenciais_invalidas".equals(motivo)) {
            return "Desconectado: login ou senha não são mais válidos";
        }
        return "Desconectado";
    }
}
