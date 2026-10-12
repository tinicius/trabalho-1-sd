package br.cefet.sd.middleware.sessao;

import br.cefet.sd.comum.protocolo.Perfil;
import br.cefet.sd.comum.protocolo.TipoTransporte;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * {@link Sessao} com fila de saida limitada e thread escritora propria (thread virtual).
 *
 * <p>{@link #enviar(String)} so enfileira, entao um Cliente lento nao atrasa a distribuicao aos
 * demais. Se a fila estourar ({@value #CAPACIDADE_PADRAO} itens por padrao), apenas esta Sessao e
 * encerrada, com o motivo {@code fila_cheia}. Se a escrita no transporte falhar, a
 * Sessao e encerrada com {@code fechada_pelo_cliente}.
 *
 * <p>Serve tanto aos listeners reais (TCP e WebSocket, cada um fornecendo seu
 * {@link DestinoDeSaida}) quanto a Sessoes falsas em testes.
 */
public final class SessaoEnfileirada implements Sessao {

    /** Tamanho maximo da fila de saida de cada Sessao. */
    public static final int CAPACIDADE_PADRAO = 1000;

    public static final String MOTIVO_FILA_CHEIA = "fila_cheia";
    public static final String MOTIVO_FECHADA_PELO_CLIENTE = "fechada_pelo_cliente";

    /** Marcador de fim usado por {@link #fecharAposEsvaziar(String)}; comparado por identidade. */
    private static final String FIM = new String("<fim>");

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private record Identidade(String id, Perfil perfil, String dispositivo, String cliente) {}

    private final TipoTransporte transporte;
    private final String endereco;
    private final DestinoDeSaida destino;
    private final BlockingQueue<String> fila;
    private final AtomicReference<Identidade> identidade = new AtomicReference<>();
    private final AtomicReference<String> motivoAposEsvaziar = new AtomicReference<>();

    private final Object trava = new Object();
    private final List<Consumer<String>> observadores = new ArrayList<>(); // guardado por trava
    private boolean finalizada; // guardado por trava
    private String motivoFinal; // guardado por trava

    private volatile boolean encerrando;
    private final Thread escritora;

    private SessaoEnfileirada(
            TipoTransporte transporte, String endereco, DestinoDeSaida destino, int capacidade) {
        if (capacidade < 1) {
            throw new IllegalArgumentException("capacidade deve ser positiva: " + capacidade);
        }
        this.transporte = Objects.requireNonNull(transporte, "transporte");
        this.endereco = endereco;
        this.destino = Objects.requireNonNull(destino, "destino");
        this.fila = new ArrayBlockingQueue<>(capacidade);
        this.escritora = Thread.ofVirtual().name("sessao-escritora").unstarted(this::escrever);
    }

    /** Abre uma Sessao com fila de {@value #CAPACIDADE_PADRAO} itens e inicia sua thread escritora. */
    public static SessaoEnfileirada abrir(
            TipoTransporte transporte, String endereco, DestinoDeSaida destino) {
        return abrir(transporte, endereco, destino, CAPACIDADE_PADRAO);
    }

    /** Abre uma Sessao com a capacidade de fila informada (util em testes). */
    public static SessaoEnfileirada abrir(
            TipoTransporte transporte, String endereco, DestinoDeSaida destino, int capacidade) {
        SessaoEnfileirada sessao = new SessaoEnfileirada(transporte, endereco, destino, capacidade);
        sessao.escritora.start();
        return sessao;
    }

    @Override
    public String id() {
        Identidade i = identidade.get();
        return i == null ? null : i.id();
    }

    @Override
    public Optional<Perfil> perfil() {
        return Optional.ofNullable(identidade.get()).map(Identidade::perfil);
    }

    @Override
    public boolean autenticada() {
        return identidade.get() != null;
    }

    @Override
    public TipoTransporte transporte() {
        return transporte;
    }

    @Override
    public String endereco() {
        return endereco;
    }

    @Override
    public String dispositivo() {
        Identidade i = identidade.get();
        return i == null ? null : i.dispositivo();
    }

    @Override
    public String cliente() {
        Identidade i = identidade.get();
        return i == null ? null : i.cliente();
    }

    @Override
    public String autenticar(Perfil perfil, String dispositivo, String cliente) {
        Objects.requireNonNull(perfil, "perfil");
        byte[] bytes = new byte[16];
        ALEATORIO.nextBytes(bytes);
        String id = HexFormat.of().formatHex(bytes);
        if (!identidade.compareAndSet(null, new Identidade(id, perfil, dispositivo, cliente))) {
            throw new IllegalStateException("Sessao ja autenticada");
        }
        return id;
    }

    @Override
    public void enviar(String json) {
        Objects.requireNonNull(json, "json");
        if (encerrando) {
            return;
        }
        if (!fila.offer(json)) {
            fechar(MOTIVO_FILA_CHEIA);
        }
    }

    @Override
    public void fechar(String motivo) {
        encerrando = true;
        finalizar(motivo);
        if (Thread.currentThread() != escritora) {
            escritora.interrupt();
        }
    }

    @Override
    public void fecharAposEsvaziar(String motivo) {
        encerrando = true;
        motivoAposEsvaziar.compareAndSet(null, motivo);
        if (!fila.offer(FIM)) {
            // Fila cheia: nao ha como esperar o escoamento sem bloquear; encerra de imediato.
            fechar(motivo);
        }
    }

    @Override
    public boolean fechada() {
        return encerrando;
    }

    @Override
    public void aoFechar(Consumer<String> observador) {
        Objects.requireNonNull(observador, "observador");
        String motivo;
        synchronized (trava) {
            if (!finalizada) {
                observadores.add(observador);
                return;
            }
            motivo = motivoFinal;
        }
        notificar(observador, motivo);
    }

    /** Laco da thread escritora: tira da fila e escreve no transporte, na ordem de chegada. */
    private void escrever() {
        try {
            while (true) {
                String quadro = fila.take();
                if (quadro == FIM) {
                    finalizar(motivoAposEsvaziar.get());
                    return;
                }
                destino.escrever(quadro);
            }
        } catch (InterruptedException e) {
            // fechar() ja finalizou a Sessao; apenas encerra a thread.
        } catch (IOException | RuntimeException e) {
            encerrando = true;
            finalizar(MOTIVO_FECHADA_PELO_CLIENTE);
        }
    }

    /** Fecha o transporte e avisa os observadores, uma unica vez; o primeiro motivo vale. */
    private void finalizar(String motivo) {
        List<Consumer<String>> avisar;
        synchronized (trava) {
            if (finalizada) {
                return;
            }
            finalizada = true;
            motivoFinal = motivo;
            avisar = new ArrayList<>(observadores);
            observadores.clear();
        }
        fila.clear();
        try {
            destino.fechar();
        } catch (RuntimeException e) {
            System.err.println("Falha ao fechar o transporte da Sessao: " + e.getClass().getName());
        }
        for (Consumer<String> observador : avisar) {
            notificar(observador, motivo);
        }
    }

    private static void notificar(Consumer<String> observador, String motivo) {
        try {
            observador.accept(motivo);
        } catch (RuntimeException e) {
            System.err.println("Falha em observador de fechamento: " + e.getClass().getName());
        }
    }
}
