package br.cefet.sd.middleware.repositorio;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Porta do Repositorio_Conversas. A implementacao em arquivo serializa as escritas;
 * os registros guardam o conteudo ja cifrado com a Chave_Repositorio.
 */
public interface RepositorioConversas {

    /**
     * Anexa o registro como uma linha propria, integra.
     *
     * @throws IOException se a gravacao falha (o chamador nao deve distribuir a mensagem)
     */
    void anexar(RegistroConversa registro) throws IOException;

    /**
     * Registros com {@code ts >= desde}, em ordem de gravacao. O limite e inclusivo: um registro
     * com {@code ts} exatamente igual a {@code desde} e devolvido. Linhas ilegiveis sao ignoradas.
     *
     * @param desde normalmente {@code Relogio.inicioJanelaConversas()}
     * @throws IOException se a leitura falha
     */
    List<RegistroConversa> janela(Instant desde) throws IOException;
}
