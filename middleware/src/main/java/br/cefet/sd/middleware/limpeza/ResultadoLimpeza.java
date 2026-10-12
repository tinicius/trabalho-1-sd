package br.cefet.sd.middleware.limpeza;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Resultado de uma execucao da {@link Limpeza}: um {@link Arquivo} por repositorio, na ordem
 * Repositorio_Conversas, Log_Envios, Log_Erros e Log_Acessos.
 *
 * @param agora instante usado para calcular os limites
 * @param arquivos resultado de cada arquivo
 */
public record ResultadoLimpeza(Instant agora, List<Arquivo> arquivos) {

    public ResultadoLimpeza {
        Objects.requireNonNull(agora, "agora");
        arquivos = List.copyOf(arquivos);
    }

    /**
     * Resultado de um arquivo.
     *
     * @param nome nome do repositorio (por exemplo {@code conversas.txt})
     * @param limite instante de corte: registros com {@code ts} menor que ele foram removidos
     * @param mantidos registros que permaneceram
     * @param removidos registros validos vencidos que foram removidos
     * @param descartados linhas descartadas por formato (JSON invalido ou sem {@code ts} valido)
     * @param falha {@code null} se o arquivo foi limpo; senao o tipo da excecao que impediu a
     *     limpeza (o arquivo original permanece intacto e os demais arquivos seguem sua limpeza)
     */
    public record Arquivo(
            String nome, Instant limite, int mantidos, int removidos, int descartados, String falha) {

        public Arquivo {
            Objects.requireNonNull(nome, "nome");
            Objects.requireNonNull(limite, "limite");
        }

        /** {@code true} se o arquivo foi limpo sem falha. */
        public boolean sucesso() {
            return falha == null;
        }
    }

    /** {@code true} se todos os arquivos foram limpos sem falha. */
    public boolean sucesso() {
        return arquivos.stream().allMatch(Arquivo::sucesso);
    }

    /** Resultado do arquivo com o nome dado, ou {@code null}. */
    public Arquivo arquivo(String nome) {
        for (Arquivo a : arquivos) {
            if (a.nome().equals(nome)) {
                return a;
            }
        }
        return null;
    }
}
