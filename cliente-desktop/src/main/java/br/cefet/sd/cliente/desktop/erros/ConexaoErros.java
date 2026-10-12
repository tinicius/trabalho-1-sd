package br.cefet.sd.cliente.desktop.erros;

import br.cefet.sd.cliente.desktop.rede.ConexaoTcp;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** O que o {@link ReportadorErros} precisa da conexao: estar aberta e enviar {@code error_report}. */
public interface ConexaoErros {

    boolean aberta();

    /** Futuro com os {@code evid} aceitos; falha com {@code ErroRequisicao}. */
    CompletableFuture<List<String>> enviarErrorReport(List<ObjectNode> eventos);

    /** Adapta a {@link ConexaoTcp} do Cliente_Desktop. */
    static ConexaoErros de(ConexaoTcp conexao) {
        return new ConexaoErros() {
            @Override
            public boolean aberta() {
                return conexao.aberta();
            }

            @Override
            public CompletableFuture<List<String>> enviarErrorReport(List<ObjectNode> eventos) {
                return conexao.enviarErrorReport(eventos);
            }
        };
    }
}
