package br.cefet.sd.middleware;

import br.cefet.sd.comum.cifra.Chave;
import br.cefet.sd.middleware.config.Configuracao;
import br.cefet.sd.middleware.config.ConfiguracaoInvalidaException;
import br.cefet.sd.middleware.contas.ContasRepositorio;
import br.cefet.sd.middleware.contas.ContasRepositorioJdbc;
import br.cefet.sd.middleware.contas.TratamentoSenhas;
import br.cefet.sd.middleware.limpeza.Limpeza;
import br.cefet.sd.middleware.limpeza.ResultadoLimpeza;
import br.cefet.sd.middleware.listener.ListenerTcp;
import br.cefet.sd.middleware.listener.ListenerWebSocket;
import br.cefet.sd.middleware.logs.LogAcessosArquivo;
import br.cefet.sd.middleware.logs.LogEnviosArquivo;
import br.cefet.sd.middleware.logs.LogErrosArquivo;
import br.cefet.sd.middleware.protocolo.EnviadorHistorico;
import br.cefet.sd.middleware.protocolo.OperacoesAutenticacao;
import br.cefet.sd.middleware.protocolo.OperacoesConversa;
import br.cefet.sd.middleware.protocolo.ProcessadorProtocolo;
import br.cefet.sd.middleware.repositorio.RepositorioConversasArquivo;
import br.cefet.sd.middleware.sessao.Distribuidor;
import br.cefet.sd.middleware.sessao.DistribuidorEmMemoria;
import br.cefet.sd.middleware.sessao.Sessao;
import br.cefet.sd.middleware.sessao.SessaoEnfileirada;
import br.cefet.sd.middleware.tempo.Relogio;
import br.cefet.sd.middleware.web.AplicacaoWebJava;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ponto de entrada do Middleware: carrega a {@link Configuracao}, monta os componentes e abre os
 * listeners.
 *
 * <h2>Uso</h2>
 *
 * <pre>java -jar middleware.jar [servidor.properties [chaves.properties]]</pre>
 *
 * Sem argumentos, le {@code config/servidor.properties} e {@code config/chaves.properties} do
 * diretorio atual; variaveis de ambiente sobrepoem as propriedades (ver {@link Configuracao}).
 * Caminhos relativos da configuracao ({@code dados.dir}, {@code web.raiz}, {@code recursos.raiz})
 * tambem sao relativos ao diretorio atual.
 *
 * <h2>Partida (ordem)</h2>
 *
 * <ol>
 *   <li>Carrega e valida a configuracao. Se for invalida (chaves iguais ou mal formadas, retencao
 *       ou intervalo menor ou igual a zero, porta fora do intervalo), recusa a partida com a lista de
 *       problemas em {@code stderr} e codigo de saida 2. As mensagens nunca contem valores de chave.
 *   <li>Cria o Repositorio_Conversas, os tres logs, o {@link ProcessadorProtocolo}, o
 *       {@link Distribuidor} e registra as seis operacoes do Protocolo.
 *   <li>Executa a {@link Limpeza} de forma <b>sincrona</b>, antes de abrir qualquer porta.
 *       Falha em um arquivo nao impede a partida: fica no Log_Erros e a proxima execucao tenta de
 *       novo.
 *   <li>Abre {@link ListenerTcp}, {@link ListenerWebSocket} e {@link AplicacaoWebJava}. Porta ocupada
 *       recusa a partida (codigo de saida 1) e fecha o que ja tinha aberto.
 *   <li>Agenda a Limpeza periodica ({@code limpeza.intervalo.minutos}).
 * </ol>
 *
 * <h2>Encerramento (ordem)</h2>
 *
 * Um shutdown hook (SIGINT/SIGTERM) chama {@link Execucao#close()}: fecha os listeners (que
 * encerram as conexoes abertas), para a Limpeza periodica e, por fim, fecha as Sessoes que ainda
 * restarem no Distribuidor. Os eventos {@code conexao} e {@code desconexao} do Log_Acessos (com o
 * motivo) sao gravados por {@link OperacoesAutenticacao}, que arma um observador em cada Sessao;
 * esta classe nao os grava por conta propria.
 */
public final class MiddlewareMain {

    static final Path SERVIDOR_PADRAO = Path.of("config", "servidor.properties");
    static final Path CHAVES_PADRAO = Path.of("config", "chaves.properties");

    private static final int SAIDA_CONFIGURACAO_INVALIDA = 2;
    private static final int SAIDA_FALHA_DE_PARTIDA = 1;

    private MiddlewareMain() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 2) {
            System.err.println("Uso: MiddlewareMain [servidor.properties [chaves.properties]]");
            System.exit(SAIDA_CONFIGURACAO_INVALIDA);
        }
        Path servidor = args.length >= 1 ? Path.of(args[0]) : SERVIDOR_PADRAO;
        Path chaves = args.length >= 2 ? Path.of(args[1]) : CHAVES_PADRAO;

        Configuracao configuracao;
        try {
            configuracao = carregarConfiguracao(servidor, chaves);
        } catch (ConfiguracaoInvalidaException e) {
            System.err.println("Partida recusada: configuracao invalida.");
            for (String problema : e.problemas()) {
                System.err.println("  - " + problema);
            }
            System.exit(SAIDA_CONFIGURACAO_INVALIDA);
            return;
        } catch (IOException e) {
            System.err.println("Partida recusada: nao foi possivel ler a configuracao ("
                    + e.getClass().getSimpleName() + ").");
            System.exit(SAIDA_CONFIGURACAO_INVALIDA);
            return;
        }

        Execucao execucao;
        try {
            execucao = iniciar(configuracao, new ContasRepositorioJdbc(configuracao));
        } catch (IOException | RuntimeException e) {
            // A mensagem de IOException/BindException descreve a porta ou o arquivo, nunca uma chave.
            System.err.println("Partida recusada: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(SAIDA_FALHA_DE_PARTIDA);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(execucao::close, "middleware-encerramento"));

        System.out.println("Middleware iniciado: TCP " + execucao.portaTcp() + ", WebSocket "
                + execucao.portaWs() + ", HTTP " + execucao.portaHttp() + ", dados em "
                + configuracao.dadosDir());
        // Mantem a thread principal viva ate o encerramento do processo (o hook faz a limpeza).
        new CountDownLatch(1).await();
    }

    /**
     * Carrega a configuracao dos dois arquivos, avisando em {@code stderr} (sem valores) quando um
     * deles nao existe: a ausencia resulta em propriedades obrigatorias faltando.
     */
    static Configuracao carregarConfiguracao(Path servidor, Path chaves) throws IOException {
        if (!Files.isRegularFile(servidor)) {
            System.err.println("Aviso: arquivo de configuracao nao encontrado: " + servidor
                    + " (usando padroes e variaveis de ambiente)");
        }
        if (!Files.isRegularFile(chaves)) {
            System.err.println("Aviso: arquivo de chaves nao encontrado: " + chaves
                    + " (as chaves devem vir das variaveis de ambiente)");
        }
        return Configuracao.carregar(servidor, chaves);
    }

    /**
     * Monta todos os componentes, executa a Limpeza de partida, abre os tres servidores e agenda a
     * Limpeza periodica. O repositorio de Contas e injetado para que o uso real seja o JDBC e os
     * testes possam trocar o Banco_de_Dados.
     *
     * @return a execucao em andamento; feche-a para encerrar de forma ordenada
     * @throws IOException se um diretorio de dados ou uma porta nao puder ser aberto; nesse caso
     *     o que ja foi aberto e fechado antes de propagar
     */
    public static Execucao iniciar(Configuracao configuracao, ContasRepositorio contas)
            throws IOException {
        Objects.requireNonNull(configuracao, "configuracao");
        Objects.requireNonNull(contas, "contas");

        Chave chaveTransito = configuracao.chaveTransito();
        Chave chaveRepositorio = configuracao.chaveRepositorio();
        Relogio relogio = Relogio.sistema();

        Path dados = configuracao.dadosDir();
        RepositorioConversasArquivo conversas = RepositorioConversasArquivo.emDiretorio(dados);
        LogErrosArquivo logErros = LogErrosArquivo.emDiretorio(dados);
        LogAcessosArquivo logAcessos = LogAcessosArquivo.emDiretorio(dados);
        LogEnviosArquivo logEnvios = LogEnviosArquivo.emDiretorio(dados);

        ProcessadorProtocolo processador = new ProcessadorProtocolo(relogio, logErros);
        Distribuidor distribuidor = new DistribuidorEmMemoria();
        EnviadorHistorico enviadorHistorico = new EnviadorHistorico(
                processador, conversas, contas, chaveRepositorio, chaveTransito, relogio);
        new OperacoesAutenticacao(
                        contas,
                        new TratamentoSenhas(configuracao.senhaIteracoes()),
                        chaveTransito,
                        distribuidor,
                        logAcessos,
                        enviadorHistorico)
                .registrarEm(processador);
        new OperacoesConversa(
                        conversas,
                        logEnvios,
                        logErros,
                        distribuidor,
                        enviadorHistorico,
                        chaveTransito,
                        chaveRepositorio)
                .registrarEm(processador);

        // Limpeza de partida: sincrona e ANTES de abrir qualquer porta.
        Limpeza limpeza = Limpeza.deConfiguracao(configuracao, conversas, logEnvios, logErros, logAcessos, relogio);
        ResultadoLimpeza resultado = limpeza.executar();
        if (!resultado.sucesso()) {
            System.err.println("Aviso: a Limpeza de partida falhou em algum arquivo (ver Log_Erros).");
        }

        Execucao execucao = new Execucao(distribuidor, resultado);
        try {
            execucao.tcp = new ListenerTcp(configuracao, processador, distribuidor);
            execucao.tcp.iniciar();
            execucao.ws = new ListenerWebSocket(configuracao, processador, distribuidor);
            execucao.ws.iniciar();
            execucao.web = AplicacaoWebJava.deConfiguracao(configuracao);
            execucao.web.iniciar();
            execucao.agendamento = limpeza.agendar(configuracao);
        } catch (IOException | RuntimeException e) {
            execucao.close();
            throw e;
        }
        return execucao;
    }

    /** Middleware em funcionamento. {@link #close()} faz o encerramento ordenado e e idempotente. */
    public static final class Execucao implements AutoCloseable {
        private final Distribuidor distribuidor;
        private final ResultadoLimpeza limpezaDePartida;
        private final AtomicBoolean encerrada = new AtomicBoolean();

        private volatile ListenerTcp tcp;
        private volatile ListenerWebSocket ws;
        private volatile AplicacaoWebJava web;
        private volatile Limpeza.Agendamento agendamento;

        private Execucao(Distribuidor distribuidor, ResultadoLimpeza limpezaDePartida) {
            this.distribuidor = distribuidor;
            this.limpezaDePartida = limpezaDePartida;
        }

        /** Porta TCP efetivamente aberta. */
        public int portaTcp() {
            return tcp.portaLocal();
        }

        /** Porta WebSocket efetivamente aberta. */
        public int portaWs() {
            return ws.portaLocal();
        }

        /** Porta HTTP efetivamente aberta. */
        public int portaHttp() {
            return web.portaEfetiva();
        }

        /** Resultado da Limpeza executada antes de abrir os listeners. */
        public ResultadoLimpeza limpezaDePartida() {
            return limpezaDePartida;
        }

        /**
         * Fecha os listeners (e com eles as conexoes abertas), para a Limpeza periodica e fecha as
         * Sessoes restantes. Cada passo e isolado: a falha de um nao impede os seguintes.
         */
        @Override
        public void close() {
            if (!encerrada.compareAndSet(false, true)) {
                return;
            }
            executar(() -> {
                ListenerTcp t = tcp;
                if (t != null) {
                    t.close();
                }
            });
            executar(() -> {
                ListenerWebSocket w = ws;
                if (w != null) {
                    w.close();
                }
            });
            executar(() -> {
                AplicacaoWebJava h = web;
                if (h != null) {
                    h.close();
                }
            });
            executar(() -> {
                Limpeza.Agendamento a = agendamento;
                if (a != null) {
                    a.close();
                }
            });
            for (Sessao sessao : distribuidor.sessoes()) {
                executar(() -> sessao.fechar(SessaoEnfileirada.MOTIVO_FECHADA_PELO_CLIENTE));
            }
        }

        private static void executar(Runnable passo) {
            try {
                passo.run();
            } catch (RuntimeException falha) {
                System.err.println("Falha no encerramento (" + falha.getClass().getName() + ")");
            }
        }
    }
}
