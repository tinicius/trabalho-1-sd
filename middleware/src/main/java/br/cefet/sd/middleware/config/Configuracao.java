package br.cefet.sd.middleware.config;

import br.cefet.sd.comum.cifra.Chave;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Configuracao do Middleware.
 *
 * <p>Os valores vem de arquivos {@code .properties} ({@code config/servidor.properties} e
 * {@code config/chaves.properties}) e podem ser sobrepostos por variaveis de ambiente. O nome da
 * variavel e o da propriedade em maiusculas, com {@code .} trocado por {@code _}
 * (por exemplo {@code retencao.logs.dias} vira {@code RETENCAO_LOGS_DIAS}); variavel vazia e
 * ignorada. Propriedades ausentes assumem os padroes.
 *
 * <p>A validacao de partida acontece na criacao: chaves com 64 hexadecimais e distintas entre si,
 * retencoes e intervalo maiores que zero. Em caso de problema lanca
 * {@link ConfiguracaoInvalidaException}, cujas mensagens nunca contem valores de chave. A
 * credencial do banco fica apenas aqui, no lado servidor.
 */
public final class Configuracao {

    public static final String DB_URL = "db.url";
    public static final String DB_USUARIO = "db.usuario";
    public static final String DB_SENHA = "db.senha";
    public static final String PORTA_TCP = "porta.tcp";
    public static final String PORTA_WS = "porta.ws";
    public static final String PORTA_HTTP = "porta.http";
    public static final String WEB_RAIZ = "web.raiz";
    public static final String RECURSOS_RAIZ = "recursos.raiz";
    public static final String DADOS_DIR = "dados.dir";
    public static final String WS_ORIGENS_PERMITIDAS = "ws.origens_permitidas";
    public static final String RETENCAO_LOGS_DIAS = "retencao.logs.dias";
    public static final String RETENCAO_ENVIOS_MESES = "retencao.envios.meses";
    public static final String LIMPEZA_INTERVALO_MINUTOS = "limpeza.intervalo.minutos";
    public static final String SENHA_ITERACOES = "senha.iteracoes";
    public static final String CHAVE_TRANSITO = "chave.transito";
    public static final String CHAVE_REPOSITORIO = "chave.repositorio";

    private final String dbUrl;
    private final String dbUsuario;
    private final String dbSenha;
    private final int portaTcp;
    private final int portaWs;
    private final int portaHttp;
    private final Path webRaiz;
    private final Path recursosRaiz;
    private final Path dadosDir;
    private final List<String> wsOrigensPermitidas;
    private final int retencaoLogsDias;
    private final int retencaoEnviosMeses;
    private final int limpezaIntervaloMinutos;
    private final int senhaIteracoes;
    private final Chave chaveTransito;
    private final Chave chaveRepositorio;

    private Configuracao(Leitor l) {
        this.dbUrl = l.texto(DB_URL, "jdbc:postgresql://localhost:5432/comunicador");
        this.dbUsuario = l.texto(DB_USUARIO, "comunicador");
        this.dbSenha = l.texto(DB_SENHA, "");
        this.portaTcp = l.porta(PORTA_TCP, 9000);
        this.portaWs = l.porta(PORTA_WS, 9001);
        this.portaHttp = l.porta(PORTA_HTTP, 8080);
        this.webRaiz = Path.of(l.texto(WEB_RAIZ, "cliente-web"));
        this.recursosRaiz = Path.of(l.texto(RECURSOS_RAIZ, "recursos"));
        this.dadosDir = Path.of(l.texto(DADOS_DIR, "dados"));
        this.wsOrigensPermitidas =
                lista(l.texto(WS_ORIGENS_PERMITIDAS, "http://localhost:" + this.portaHttp));
        this.retencaoLogsDias = l.positivo(RETENCAO_LOGS_DIAS, 90);
        this.retencaoEnviosMeses = l.positivo(RETENCAO_ENVIOS_MESES, 12);
        this.limpezaIntervaloMinutos = l.positivo(LIMPEZA_INTERVALO_MINUTOS, 60);
        this.senhaIteracoes = l.positivo(SENHA_ITERACOES, 100_000);
        this.chaveTransito = l.chave(CHAVE_TRANSITO);
        this.chaveRepositorio = l.chave(CHAVE_REPOSITORIO);
        if (chaveTransito != null && chaveTransito.equals(chaveRepositorio)) {
            l.problema(CHAVE_TRANSITO + " e " + CHAVE_REPOSITORIO + " devem ser diferentes entre si");
        }
        l.exigirValido();
    }

    /**
     * Carrega a configuracao dos dois arquivos e do ambiente do processo.
     *
     * @param servidor caminho de {@code servidor.properties}; arquivo inexistente equivale a vazio
     * @param chaves caminho de {@code chaves.properties}; arquivo inexistente equivale a vazio
     * @throws IOException se um arquivo existente nao puder ser lido
     * @throws ConfiguracaoInvalidaException se algum valor violar a validacao de partida
     */
    public static Configuracao carregar(Path servidor, Path chaves) throws IOException {
        return carregar(servidor, chaves, System.getenv());
    }

    /** Como {@link #carregar(Path, Path)}, com o ambiente informado (para testes). */
    public static Configuracao carregar(Path servidor, Path chaves, Map<String, String> ambiente)
            throws IOException {
        Map<String, String> propriedades = new HashMap<>();
        lerArquivo(servidor, propriedades);
        lerArquivo(chaves, propriedades);
        return deValores(propriedades, ambiente);
    }

    /**
     * Cria a configuracao a partir de propriedades ja lidas e de um ambiente, sem tocar o disco.
     *
     * @param propriedades pares nome/valor (podem estar incompletos)
     * @param ambiente variaveis de ambiente que sobrepoem as propriedades
     */
    public static Configuracao deValores(
            Map<String, String> propriedades, Map<String, String> ambiente) {
        return new Configuracao(new Leitor(propriedades, ambiente));
    }

    /** Nome da variavel de ambiente que sobrepoe a propriedade, por exemplo {@code PORTA_TCP}. */
    public static String nomeVariavelAmbiente(String propriedade) {
        return propriedade.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    private static void lerArquivo(Path caminho, Map<String, String> destino) throws IOException {
        if (caminho == null || !Files.isRegularFile(caminho)) {
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(caminho, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        for (String nome : p.stringPropertyNames()) {
            destino.put(nome, p.getProperty(nome));
        }
    }

    private static List<String> lista(String valor) {
        return Arrays.stream(valor.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public String dbUrl() {
        return dbUrl;
    }

    public String dbUsuario() {
        return dbUsuario;
    }

    public String dbSenha() {
        return dbSenha;
    }

    public int portaTcp() {
        return portaTcp;
    }

    public int portaWs() {
        return portaWs;
    }

    public int portaHttp() {
        return portaHttp;
    }

    public Path webRaiz() {
        return webRaiz;
    }

    public Path recursosRaiz() {
        return recursosRaiz;
    }

    public Path dadosDir() {
        return dadosDir;
    }

    /** Origens aceitas no handshake WebSocket. */
    public List<String> wsOrigensPermitidas() {
        return wsOrigensPermitidas;
    }

    /** Retencao_Logs em dias (Log_Erros e Log_Acessos); padrao 90. */
    public int retencaoLogsDias() {
        return retencaoLogsDias;
    }

    /** Retencao do Log_Envios em meses de calendario; padrao 12. */
    public int retencaoEnviosMeses() {
        return retencaoEnviosMeses;
    }

    /** Intervalo_Limpeza em minutos; padrao 60. */
    public int limpezaIntervaloMinutos() {
        return limpezaIntervaloMinutos;
    }

    /** Intervalo_Limpeza como {@link Duration}. */
    public Duration intervaloLimpeza() {
        return Duration.ofMinutes(limpezaIntervaloMinutos);
    }

    /** Iteracoes do SHA-256 do Tratamento_de_Senhas para novas contas; padrao 100000. */
    public int senhaIteracoes() {
        return senhaIteracoes;
    }

    /** Chave_de_Canal de Transito. */
    public Chave chaveTransito() {
        return chaveTransito;
    }

    /** Chave_de_Canal do Repositorio_Servidor. */
    public Chave chaveRepositorio() {
        return chaveRepositorio;
    }

    /** Resumo sem chaves nem senha de banco. */
    @Override
    public String toString() {
        return "Configuracao[dbUrl=" + dbUrl + ", dbUsuario=" + dbUsuario + ", dbSenha=***"
                + ", portaTcp=" + portaTcp + ", portaWs=" + portaWs + ", portaHttp=" + portaHttp
                + ", dadosDir=" + dadosDir + ", retencaoLogsDias=" + retencaoLogsDias
                + ", retencaoEnviosMeses=" + retencaoEnviosMeses
                + ", limpezaIntervaloMinutos=" + limpezaIntervaloMinutos + ", chaves=***]";
    }

    /** Leitura com sobreposicao por ambiente que acumula problemas em vez de falhar no primeiro. */
    private static final class Leitor {
        private final Map<String, String> propriedades;
        private final Map<String, String> ambiente;
        private final List<String> problemas = new ArrayList<>();

        Leitor(Map<String, String> propriedades, Map<String, String> ambiente) {
            this.propriedades = propriedades == null ? Map.of() : propriedades;
            this.ambiente = ambiente == null ? Map.of() : ambiente;
        }

        void problema(String texto) {
            problemas.add(texto);
        }

        void exigirValido() {
            if (!problemas.isEmpty()) {
                throw new ConfiguracaoInvalidaException(problemas);
            }
        }

        /** Valor bruto: ambiente (nao vazio) vence o arquivo; {@code null} se ausente. */
        private String bruto(String nome) {
            String doAmbiente = ambiente.get(nomeVariavelAmbiente(nome));
            if (doAmbiente != null && !doAmbiente.isBlank()) {
                return doAmbiente.trim();
            }
            String doArquivo = propriedades.get(nome);
            return doArquivo == null ? null : doArquivo.trim();
        }

        String texto(String nome, String padrao) {
            String v = bruto(nome);
            return v == null || v.isEmpty() ? padrao : v;
        }

        /** Inteiro; o valor recebido nunca entra na mensagem de erro. */
        private int inteiro(String nome, int padrao) {
            String v = bruto(nome);
            if (v == null || v.isEmpty()) {
                return padrao;
            }
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                problema(nome + " deve ser um numero inteiro");
                return padrao;
            }
        }

        int positivo(String nome, int padrao) {
            int v = inteiro(nome, padrao);
            if (v <= 0) {
                problema(nome + " deve ser maior que zero");
                return padrao;
            }
            return v;
        }

        int porta(String nome, int padrao) {
            int v = inteiro(nome, padrao);
            if (v < 1 || v > 65535) {
                problema(nome + " deve estar entre 1 e 65535");
                return padrao;
            }
            return v;
        }

        /** Chave de 64 hexadecimais; {@code null} (com problema registrado) se ausente ou invalida. */
        Chave chave(String nome) {
            String v = bruto(nome);
            if (v == null || v.isEmpty()) {
                problema(nome + " ausente: informe " + Chave.TAMANHO_HEX + " caracteres hexadecimais");
                return null;
            }
            try {
                return Chave.deHex(v);
            } catch (IllegalArgumentException e) {
                // A mensagem de Chave.deHex nao ecoa o valor; ainda assim usamos texto proprio.
                problema(nome + " invalida: esperados " + Chave.TAMANHO_HEX
                        + " caracteres hexadecimais");
                return null;
            }
        }
    }
}
