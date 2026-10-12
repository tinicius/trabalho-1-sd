package br.cefet.sd.cliente.desktop.erros;

import br.cefet.sd.cliente.desktop.erros.ArmazenamentoErros.Estado;
import br.cefet.sd.comum.protocolo.ProtocoloJson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Buffer_Offline_Erros do Cliente_Desktop.
 *
 * <p>Comportamento identico ao de {@code cliente-web/js/erros.js} e {@code
 * cliente-python/comunicador/nucleo/erros.py}:
 *
 * <ul>
 *   <li>no maximo 100 eventos e 256 KiB (JSON compacto em UTF-8); ao exceder, descarta o mais
 *       antigo (FIFO) e soma 1 ao contador {@code descartados};
 *   <li>no maximo 5 tentativas por evento; ao esgotar, o evento e descartado e contado;
 *   <li>o contador {@code descartados} segue no primeiro evento do proximo lote e so e abatido
 *       quando esse evento e confirmado;
 *   <li>lotes de ate 20 eventos e 60 KiB; so os {@code evid} presentes em {@code aceitos} saem do
 *       buffer.
 * </ul>
 *
 * <p>Todas as operacoes sao serializadas por um lock, porque cada uma le o estado, o altera e o grava
 * de volta. A classe nao conhece rede, relogio nem interface grafica.
 */
public final class BufferOfflineErros {

    /** Maximo de eventos retidos. */
    public static final int MAXIMO_EVENTOS_BUFFER = 100;

    /** Maximo de bytes retidos: 256 KiB. */
    public static final int MAXIMO_BYTES_BUFFER = 256 * 1024;

    /** Maximo de tentativas de envio por evento. */
    public static final int MAXIMO_TENTATIVAS_EVENTO = 5;

    /** Maximo de eventos por {@code error_report}. */
    public static final int MAXIMO_EVENTOS_LOTE = ProtocoloJson.MAXIMO_EVENTOS_ERRO;

    /** Tamanho maximo de um {@code error_report} (o Middleware aceita 64 KiB): 60 KiB. */
    public static final int MAXIMO_BYTES_LOTE = 60 * 1024;

    /** Lote selecionado para um {@code error_report}. */
    public record Lote(
            List<ObjectNode> eventos, List<String> evids, int descartadosEnviados, String evidComContador) {}

    /** Resultado de {@link #adicionar}. */
    public record ResultadoAdicao(boolean retido, int descartadosAgora) {}

    /** Resultado de {@link #resolverLote}. */
    public record ResultadoLote(int removidos, int descartadosAgora, int restantes) {}

    private final ArmazenamentoErros armazenamento;
    private final int maxEventos;
    private final int maxBytes;
    private final int maxTentativas;
    private final Object lock = new Object();

    public BufferOfflineErros(ArmazenamentoErros armazenamento) {
        this(armazenamento, MAXIMO_EVENTOS_BUFFER, MAXIMO_BYTES_BUFFER, MAXIMO_TENTATIVAS_EVENTO);
    }

    public BufferOfflineErros(ArmazenamentoErros armazenamento, int maxEventos, int maxBytes, int maxTentativas) {
        this.armazenamento = armazenamento;
        this.maxEventos = maxEventos;
        this.maxBytes = maxBytes;
        this.maxTentativas = maxTentativas;
    }

    // ------------------------------------------------------------------
    // Funcoes puras
    // ------------------------------------------------------------------

    /** Tamanho, em bytes, do JSON compacto UTF-8 do evento retido. */
    public static int tamanhoEmBytes(EventoPendente evento) {
        return EventosJson.tamanhoEmBytes(EventosJson.paraArquivo(evento));
    }

    /**
     * Aplica os limites a {@code estado}: enquanto houver mais de {@code maxEventos} eventos ou mais
     * de {@code maxBytes} bytes, descarta o mais antigo e soma 1 a {@code descartados}. Nao altera
     * {@code estado}.
     */
    public static Estado aplicarLimites(Estado estado, int maxEventos, int maxBytes) {
        List<EventoPendente> eventos = estado.eventos();
        long total = 0;
        int[] tamanhos = new int[eventos.size()];
        for (int i = 0; i < tamanhos.length; i++) {
            tamanhos[i] = tamanhoEmBytes(eventos.get(i));
            total += tamanhos[i];
        }
        int descartados = estado.descartados();
        int inicio = 0;
        while (inicio < eventos.size() && (eventos.size() - inicio > maxEventos || total > maxBytes)) {
            total -= tamanhos[inicio];
            inicio++;
            descartados++;
        }
        return new Estado(eventos.subList(inicio, eventos.size()), descartados);
    }

    // ------------------------------------------------------------------
    // Consulta
    // ------------------------------------------------------------------

    /** Eventos retidos, do mais antigo ao mais novo. */
    public List<EventoPendente> listar() {
        synchronized (lock) {
            return armazenamento.carregar().eventos();
        }
    }

    public int tamanho() {
        synchronized (lock) {
            return armazenamento.carregar().eventos().size();
        }
    }

    /** Descartes ainda nao informados ao Middleware. */
    public int descartados() {
        synchronized (lock) {
            return armazenamento.carregar().descartados();
        }
    }

    // ------------------------------------------------------------------
    // Operacoes
    // ------------------------------------------------------------------

    /**
     * Retem {@code evento}. Ignora um {@code evid} ja presente. Se estourar os limites, descarta os
     * mais antigos.
     */
    public ResultadoAdicao adicionar(EventoPendente evento) {
        synchronized (lock) {
            Estado estado = armazenamento.carregar();
            for (EventoPendente existente : estado.eventos()) {
                if (existente.evid().equals(evento.evid())) {
                    return new ResultadoAdicao(true, 0);
                }
            }
            List<EventoPendente> todos = new ArrayList<>(estado.eventos());
            todos.add(evento);
            Estado limitado = aplicarLimites(new Estado(todos, estado.descartados()), maxEventos, maxBytes);
            armazenamento.gravar(limitado);
            boolean retido = limitado.eventos().stream().anyMatch(e -> e.evid().equals(evento.evid()));
            return new ResultadoAdicao(retido, limitado.descartados() - estado.descartados());
        }
    }

    /**
     * Seleciona o proximo lote: do mais antigo ao mais novo, ate {@code maxEventos} e {@code
     * maxBytes} (ao menos um). {@code apenas} (se nao {@code null}) restringe a esses {@code evid}.
     * O primeiro evento leva o contador {@code descartados}; os demais, zero.
     *
     * @return o lote, ou {@code null} se nao ha o que enviar
     */
    public Lote prepararLote(Set<String> apenas, int maxEventosLote, int maxBytesLote) {
        synchronized (lock) {
            Estado estado = armazenamento.carregar();
            List<ObjectNode> eventos = new ArrayList<>();
            List<String> evids = new ArrayList<>();
            long total = 0;
            for (EventoPendente candidato : estado.eventos()) {
                if (apenas != null && !apenas.contains(candidato.evid())) {
                    continue;
                }
                if (eventos.size() >= maxEventosLote) {
                    break;
                }
                ObjectNode forma = EventosJson.paraEnvio(candidato, eventos.isEmpty() ? estado.descartados() : 0);
                int tamanho = EventosJson.tamanhoEmBytes(forma);
                if (!eventos.isEmpty() && total + tamanho > maxBytesLote) {
                    break;
                }
                eventos.add(forma);
                evids.add(candidato.evid());
                total += tamanho;
            }
            if (eventos.isEmpty()) {
                return null;
            }
            return new Lote(
                    List.copyOf(eventos), List.copyOf(evids), eventos.get(0).get("descartados").asInt(), evids.get(0));
        }
    }

    /** {@link #prepararLote(Set, int, int)} com os limites padrao (20 eventos, 60 KiB). */
    public Lote prepararLote(Set<String> apenas) {
        return prepararLote(apenas, MAXIMO_EVENTOS_LOTE, MAXIMO_BYTES_LOTE);
    }

    /**
     * Registra o resultado do envio de {@code lote}.
     *
     * <ul>
     *   <li>Remove SOMENTE os eventos do lote cujo {@code evid} consta de {@code aceitos}; se o
     *       evento que levava o contador foi confirmado, abate {@code descartadosEnviados}.
     *   <li>Se {@code contarTentativa}, os demais eventos do lote ganham uma tentativa e, ao chegar
     *       ao maximo, sao descartados e contados.
     * </ul>
     */
    public ResultadoLote resolverLote(Lote lote, Iterable<String> aceitos, boolean contarTentativa) {
        synchronized (lock) {
            Estado estado = armazenamento.carregar();
            Set<String> confirmados = new HashSet<>();
            if (aceitos != null) {
                for (String a : aceitos) {
                    if (a != null) {
                        confirmados.add(a);
                    }
                }
            }
            Set<String> doLote = new HashSet<>(lote.evids());
            int descartados = estado.descartados();
            int removidos = 0;
            int descartadosAgora = 0;
            List<EventoPendente> eventos = new ArrayList<>();
            for (EventoPendente evento : estado.eventos()) {
                if (!doLote.contains(evento.evid())) {
                    eventos.add(evento);
                    continue;
                }
                if (confirmados.contains(evento.evid())) {
                    removidos++;
                    if (evento.evid().equals(lote.evidComContador())) {
                        descartados = Math.max(0, descartados - lote.descartadosEnviados());
                    }
                    continue;
                }
                if (!contarTentativa) {
                    eventos.add(evento);
                    continue;
                }
                int tentativas = evento.tentativas() + 1;
                if (tentativas >= maxTentativas) {
                    descartados++;
                    descartadosAgora++;
                    continue;
                }
                eventos.add(evento.comTentativas(tentativas));
            }
            armazenamento.gravar(new Estado(eventos, descartados));
            return new ResultadoLote(removidos, descartadosAgora, eventos.size());
        }
    }
}
