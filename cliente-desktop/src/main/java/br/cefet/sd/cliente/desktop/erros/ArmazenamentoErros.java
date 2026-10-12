package br.cefet.sd.cliente.desktop.erros;

import java.util.List;

/**
 * Armazenamento do Buffer_Offline_Erros. A logica do buffer ({@link BufferOfflineErros}) nao conhece
 * rede nem arquivos: opera sobre esta interface (memoria nos testes, arquivo em producao).
 *
 * <p>Falhas de E/S sao sinalizadas por {@link java.io.UncheckedIOException}.
 */
public interface ArmazenamentoErros {

    /** Conteudo do buffer: eventos do mais antigo ao mais novo e descartes ainda nao informados. */
    record Estado(List<EventoPendente> eventos, int descartados) {
        public Estado {
            eventos = List.copyOf(eventos);
        }

        public static Estado vazio() {
            return new Estado(List.of(), 0);
        }
    }

    Estado carregar();

    void gravar(Estado estado);
}
