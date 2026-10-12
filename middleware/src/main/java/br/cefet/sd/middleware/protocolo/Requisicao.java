package br.cefet.sd.middleware.protocolo;

import br.cefet.sd.comum.protocolo.CodigoErro;
import br.cefet.sd.comum.protocolo.ProtocoloJson;
import br.cefet.sd.middleware.sessao.Sessao;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Requisicao ja validada no envelope, entregue a um {@link ManipuladorOperacao}.
 *
 * @param processador processador que a recebeu (acesso a {@code ordemLock}, relogio e Log_Erros)
 * @param sessao conexao de origem; a identidade do remetente vem daqui, nunca do payload
 * @param quadro objeto JSON completo (campos desconhecidos devem ser ignorados)
 * @param op operacao, uma das {@link ProtocoloJson#OPERACOES_REQUISICAO}
 * @param id identificador de correlacao (1 a 64 caracteres), ou {@code null} se ausente
 * @param estado estado de protocolo desta conexao
 */
public record Requisicao(
        ProcessadorProtocolo processador,
        Sessao sessao,
        ObjectNode quadro,
        String op,
        String id,
        EstadoConexao estado) {

    /** Resposta de sucesso {@code {v, op, id, ok:true}} para o chamador acrescentar campos. */
    public ObjectNode novaRespostaOk() {
        return ProtocoloJson.respostaOk(op, id);
    }

    /** Serializa e enfileira o quadro na Sessao de origem. */
    public void enviar(ObjectNode quadroDeResposta) {
        sessao.enviar(ProtocoloJson.serializar(quadroDeResposta));
    }

    /** Responde {@code ok:true} sem campos adicionais. */
    public void responderOk() {
        enviar(novaRespostaOk());
    }

    /** Responde {@code ok:false} com o codigo (texto fixo, sem eco de dados recebidos). */
    public void responderErro(CodigoErro codigo) {
        responderErro(codigo, null);
    }

    /** Responde {@code ok:false} indicando em {@code erro.campo} o campo problematico. */
    public void responderErro(CodigoErro codigo, String campo) {
        enviar(ProtocoloJson.respostaErro(op, id, codigo, campo));
    }
}
