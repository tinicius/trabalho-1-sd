package br.cefet.sd.middleware.limpeza;

import br.cefet.sd.middleware.config.Configuracao;
import br.cefet.sd.middleware.logs.EventoErro;
import br.cefet.sd.middleware.logs.LogAcessosArquivo;
import br.cefet.sd.middleware.logs.LogErros;
import br.cefet.sd.middleware.logs.LogErrosArquivo;
import br.cefet.sd.middleware.logs.LogEnviosArquivo;
import br.cefet.sd.middleware.repositorio.RegistroJson;
import br.cefet.sd.middleware.repositorio.RepositorioArquivo;
import br.cefet.sd.middleware.repositorio.RepositorioConversasArquivo;
import br.cefet.sd.middleware.tempo.Relogio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Limpeza dos repositorios em arquivo.
 *
 * <p>Retencoes, todas calculadas pelo relogio do servidor em UTC, com {@code ts} igual ao limite
 * preservado:
 *
 * <ul>
 *   <li>Repositorio_Conversas: Janela_7_Dias ({@code ts >= agora - 7 dias});
 *   <li>Log_Envios: {@code retencao.envios.meses} meses de calendario (padrao 12);
 *   <li>Log_Erros e Log_Acessos: {@code retencao.logs.dias} dias (padrao 90).
 * </ul>
 *
 * <p>A parte pura esta em {@link #filtrarVencidos}; a E/S usa
 * {@link RepositorioArquivo#reescrever}, que mantem o lock do arquivo durante a leitura, o filtro
 * e a troca atomica, de modo que anexacoes concorrentes esperam e nenhuma e perdida.
 * Um arquivo que falha nao impede a limpeza dos demais. A contagem de linhas invalidas descartadas
 * e relatada no Log_Erros somente depois de liberado o lock do arquivo, apenas com o nome do
 * arquivo e a contagem (nunca o conteudo da linha).
 */
public final class Limpeza {

    /** Modulo registrado nos eventos do Log_Erros gerados por esta classe. */
    public static final String MODULO = "Limpeza";

    private final Alvo conversas;
    private final Alvo envios;
    private final Alvo erros;
    private final Alvo acessos;
    private final int retencaoEnviosMeses;
    private final int retencaoLogsDias;
    private final Relogio relogio;
    private final LogErros logErros;
    /** Serializa execucoes (partida e periodica), embora os locks por arquivo ja protejam os dados. */
    private final ReentrantLock execucao = new ReentrantLock();

    /**
     * @param retencaoEnviosMeses meses de calendario de retencao do Log_Envios (&gt; 0)
     * @param retencaoLogsDias dias de retencao do Log_Erros e do Log_Acessos (&gt; 0)
     * @param relogio fonte do instante usado por {@link #executar()} e pelo agendamento
     * @param logErros destino do relato de linhas invalidas e de falhas por arquivo
     */
    public Limpeza(
            RepositorioConversasArquivo conversas,
            LogEnviosArquivo envios,
            LogErrosArquivo erros,
            LogAcessosArquivo acessos,
            int retencaoEnviosMeses,
            int retencaoLogsDias,
            Relogio relogio,
            LogErros logErros) {
        this(
                conversas.arquivo(),
                envios.arquivo(),
                erros.arquivo(),
                acessos.arquivo(),
                retencaoEnviosMeses,
                retencaoLogsDias,
                relogio,
                logErros);
    }

    /** Como o construtor principal, diretamente sobre os {@link RepositorioArquivo}. */
    public Limpeza(
            RepositorioArquivo conversas,
            RepositorioArquivo envios,
            RepositorioArquivo erros,
            RepositorioArquivo acessos,
            int retencaoEnviosMeses,
            int retencaoLogsDias,
            Relogio relogio,
            LogErros logErros) {
        if (retencaoEnviosMeses <= 0) {
            throw new IllegalArgumentException("retencaoEnviosMeses deve ser maior que zero");
        }
        if (retencaoLogsDias <= 0) {
            throw new IllegalArgumentException("retencaoLogsDias deve ser maior que zero");
        }
        this.conversas = new Alvo(Objects.requireNonNull(conversas, "conversas"));
        this.envios = new Alvo(Objects.requireNonNull(envios, "envios"));
        this.erros = new Alvo(Objects.requireNonNull(erros, "erros"));
        this.acessos = new Alvo(Objects.requireNonNull(acessos, "acessos"));
        this.retencaoEnviosMeses = retencaoEnviosMeses;
        this.retencaoLogsDias = retencaoLogsDias;
        this.relogio = Objects.requireNonNull(relogio, "relogio");
        this.logErros = Objects.requireNonNull(logErros, "logErros");
    }

    /** Cria a Limpeza com as retencoes da {@link Configuracao}. */
    public static Limpeza deConfiguracao(
            Configuracao configuracao,
            RepositorioConversasArquivo conversas,
            LogEnviosArquivo envios,
            LogErrosArquivo erros,
            LogAcessosArquivo acessos,
            Relogio relogio) {
        return new Limpeza(
                conversas,
                envios,
                erros,
                acessos,
                configuracao.retencaoEnviosMeses(),
                configuracao.retencaoLogsDias(),
                relogio,
                erros);
    }

    // ------------------------------------------------------------------ parte pura

    /**
     * Resultado de {@link #filtrarVencidos}.
     *
     * @param mantidas linhas validas com {@code ts >= limite}, na ordem original e sem alteracao
     * @param removidos quantas linhas validas tinham {@code ts < limite}
     * @param descartados quantas linhas foram descartadas por formato
     */
    public record Filtrado(List<String> mantidas, int removidos, int descartados) {
        public Filtrado {
            mantidas = List.copyOf(mantidas);
        }
    }

    /**
     * Funcao pura (sem E/S): mantem a linha que e um objeto JSON valido com {@code ts} ISO-8601
     * valido e {@code ts >= limite}, preservando a ordem e o texto original; conta as linhas com
     * {@code ts < limite} como removidas e as que nao sao JSON objeto valido ou nao tem {@code ts}
     * textual valido como descartadas por formato.
     */
    public static Filtrado filtrarVencidos(List<String> linhas, Instant limite) {
        Objects.requireNonNull(linhas, "linhas");
        Objects.requireNonNull(limite, "limite");
        List<String> mantidas = new ArrayList<>(linhas.size());
        int removidos = 0;
        int descartados = 0;
        for (String linha : linhas) {
            Instant ts = tsDaLinha(linha);
            if (ts == null) {
                descartados++;
            } else if (ts.isBefore(limite)) {
                removidos++;
            } else {
                mantidas.add(linha);
            }
        }
        return new Filtrado(mantidas, removidos, descartados);
    }

    /** {@code ts} da linha, ou {@code null} se a linha nao e objeto JSON valido com {@code ts} valido. */
    private static Instant tsDaLinha(String linha) {
        if (linha == null) {
            return null;
        }
        try {
            ObjectNode no = RegistroJson.lerObjeto(linha);
            JsonNode ts = no.get("ts");
            if (ts == null || !ts.isTextual()) {
                return null;
            }
            return RegistroJson.lerTs(ts.textValue());
        } catch (IllegalArgumentException invalida) {
            return null;
        }
    }

    // ------------------------------------------------------------------ limites

    /** Inicio da retencao do Repositorio_Conversas: {@code agora - 7 dias}. */
    public static Instant limiteConversas(Instant agora) {
        return agora.minus(Relogio.JANELA_CONVERSAS);
    }

    /** Inicio da retencao do Log_Envios: {@code agora - meses}, em meses de calendario UTC. */
    public static Instant limiteEnvios(Instant agora, int meses) {
        return agora.atZone(ZoneOffset.UTC).minusMonths(meses).toInstant();
    }

    /** Inicio da retencao do Log_Erros e do Log_Acessos: {@code agora - dias}. */
    public static Instant limiteLogs(Instant agora, int dias) {
        return agora.minus(Duration.ofDays(dias));
    }

    // ------------------------------------------------------------------ execucao

    /** Executa a Limpeza com o instante do relogio injetado. */
    public ResultadoLimpeza executar() {
        return executar(relogio.agora());
    }

    /**
     * Executa a Limpeza em cada um dos quatro arquivos. Nao lanca excecao: a falha em um arquivo
     * fica registrada no {@link ResultadoLimpeza.Arquivo#falha()} e no Log_Erros, e os demais
     * arquivos sao limpos normalmente.
     *
     * @param agora instante de referencia dos limites
     */
    public ResultadoLimpeza executar(Instant agora) {
        Objects.requireNonNull(agora, "agora");
        execucao.lock();
        try {
            List<ResultadoLimpeza.Arquivo> resultados = new ArrayList<>(4);
            resultados.add(limpar(conversas, limiteConversas(agora), agora));
            resultados.add(limpar(envios, limiteEnvios(agora, retencaoEnviosMeses), agora));
            resultados.add(limpar(erros, limiteLogs(agora, retencaoLogsDias), agora));
            resultados.add(limpar(acessos, limiteLogs(agora, retencaoLogsDias), agora));
            return new ResultadoLimpeza(agora, resultados);
        } finally {
            execucao.unlock();
        }
    }

    private ResultadoLimpeza.Arquivo limpar(Alvo alvo, Instant limite, Instant agora) {
        String nome = alvo.repositorio.caminho().getFileName().toString();
        Filtrado[] capturado = new Filtrado[1];
        try {
            alvo.repositorio.reescrever(linhas -> {
                Filtrado f = filtrarVencidos(linhas, limite);
                capturado[0] = f;
                return f.mantidas();
            });
        } catch (Exception falha) {
            // reescrever deixa o arquivo original intacto; segue para os demais arquivos
            relatar(agora, nome, falha);
            return new ResultadoLimpeza.Arquivo(nome, limite, 0, 0, 0, falha.getClass().getSimpleName());
        }
        Filtrado f = capturado[0];
        // O lock do arquivo ja foi liberado: relata linhas invalidas sem segurar o lock.
        if (f.descartados() > 0) {
            relatarDescartes(agora, nome, f.descartados());
        }
        return new ResultadoLimpeza.Arquivo(
                nome, limite, f.mantidas().size(), f.removidos(), f.descartados(), null);
    }

    private void relatarDescartes(Instant agora, String nome, int quantidade) {
        try {
            logErros.registrar(new EventoErro(
                    agora,
                    EventoErro.Origem.MIDDLEWARE,
                    MODULO,
                    "LinhasInvalidasDescartadas",
                    quantidade + " linha(s) invalida(s) descartada(s) em " + nome,
                    "",
                    null,
                    null,
                    null,
                    null,
                    0));
        } catch (RuntimeException ignorada) {
            System.err.println("Falha ao relatar linhas invalidas da Limpeza em " + nome);
        }
    }

    private void relatar(Instant agora, String nome, Exception falha) {
        try {
            logErros.registrar(EventoErro.doMiddleware(
                    agora, MODULO, new RuntimeException("Falha ao limpar " + nome, falha), null));
        } catch (RuntimeException ignorada) {
            System.err.println("Falha ao relatar erro da Limpeza em " + nome);
        }
    }

    // ------------------------------------------------------------------ agendamento

    /**
     * Agenda a Limpeza periodica com {@code scheduleWithFixedDelay}: a primeira execucao agendada
     * ocorre depois de {@code intervalo}, pois a execucao da partida e feita de forma sincrona por
     * quem chama {@link #executar()} antes de abrir os listeners.
     * O corpo agendado captura toda excecao, porque uma excecao nao tratada cancelaria o
     * agendamento silenciosamente.
     *
     * @param intervalo Intervalo_Limpeza (&gt; 0)
     */
    public Agendamento agendar(Duration intervalo) {
        Objects.requireNonNull(intervalo, "intervalo");
        if (intervalo.isZero() || intervalo.isNegative()) {
            throw new IllegalArgumentException("intervalo deve ser maior que zero");
        }
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "limpeza-periodica");
            t.setDaemon(true);
            return t;
        });
        long nanos = intervalo.toNanos();
        executor.scheduleWithFixedDelay(this::corpoAgendado, nanos, nanos, TimeUnit.NANOSECONDS);
        return new Agendamento(executor);
    }

    /** Agenda com o Intervalo_Limpeza da configuracao. */
    public Agendamento agendar(Configuracao configuracao) {
        return agendar(configuracao.intervaloLimpeza());
    }

    private void corpoAgendado() {
        try {
            executar();
        } catch (Throwable falha) {
            // Nunca propaga: cancelaria as execucoes seguintes.
            System.err.println("Falha inesperada na Limpeza periodica: " + falha.getClass().getName());
        }
    }

    /** Controle da Limpeza periodica. */
    public static final class Agendamento implements AutoCloseable {
        private final ScheduledExecutorService executor;

        private Agendamento(ScheduledExecutorService executor) {
            this.executor = executor;
        }

        /** Para novas execucoes e espera ate 10 s por uma execucao em andamento. */
        @Override
        public void close() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private record Alvo(RepositorioArquivo repositorio) {}
}
