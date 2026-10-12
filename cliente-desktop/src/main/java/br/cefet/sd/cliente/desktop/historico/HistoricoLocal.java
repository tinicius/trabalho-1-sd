package br.cefet.sd.cliente.desktop.historico;

import br.cefet.sd.cliente.desktop.rede.MensagemRecebida;
import br.cefet.sd.comum.cifra.Chave;
import br.cefet.sd.comum.cifra.CifraManual;
import br.cefet.sd.comum.cifra.DecifragemFalhouException;
import br.cefet.sd.comum.protocolo.MensagemChat;
import br.cefet.sd.comum.protocolo.Perfil;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Historico_Local do Cliente_Desktop em arquivo.
 *
 * <p>Mesma semantica de {@code cliente-python/comunicador/nucleo/historico.py} e de {@code
 * cliente-web/js/historico.js}:
 *
 * <ul>
 *   <li>Um diretorio por usuario: {@code <raiz>/.comunicador/desktop/<login>/}; cada
 *       dispositivo tem o proprio {@code ~/}. Nele ficam {@code historico.txt} (um JSON
 *       por linha, mesmo formato do Repositorio_Conversas, com {@code conteudo} cifrado com a
 *       Chave_Historico) e {@code historico.key} (32 bytes aleatorios em hexadecimal,
 *       gerados neste dispositivo e nunca transmitidos).
 *   <li>A Chave_Historico e distinta da Chave_Transito.
 *   <li>Incorporacao idempotente por {@code mid}: o mapa {@code mid -> registro}
 *       e carregado na abertura e so os novos sao anexados. Duplicatas no mesmo lote: vale a
 *       primeira.
 *   <li>Nada e removido automaticamente. Cada dispositivo recifra com a propria chave e
 *       um nonce novo.
 *   <li>Exibicao ordenada por {@code (ts, mid)}, nao pela ordem do arquivo. Linhas ilegiveis sao
 *       ignoradas; a ultima linha incompleta (sem {@code \n}, queda durante a escrita) tambem e
 *       ignorada e descartada antes do proximo anexo.
 * </ul>
 *
 * <p>Seguro para uso entre threads.
 */
public final class HistoricoLocal implements AutoCloseable {

    /** Nome do arquivo do historico. */
    public static final String NOME_ARQUIVO_HISTORICO = "historico.txt";

    /** Nome do arquivo da Chave_Historico. */
    public static final String NOME_ARQUIVO_CHAVE = "historico.key";

    private static final Pattern REGEX_LOGIN = Pattern.compile("[a-z0-9_]{3,20}");
    private static final Pattern REGEX_CHAVE_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, true);

    private static final Comparator<Registro> POR_TS_MID =
            Comparator.comparing(Registro::ts).thenComparing(Registro::mid);

    /** Resultado de {@code incorporar}: quantas mensagens cada categoria teve. */
    public record ResultadoIncorporacao(int adicionadas, int existentes, int ilegiveis, int invalidas) {}

    /** Registro como gravado: {@code conteudo} cifrado com a Chave_Historico. */
    public record Registro(String mid, Perfil remetente, String ts, MensagemChat.Tipo tipo, String conteudo) {}

    /** Entrada normalizada, vinda de {@link MensagemChat} ou de {@link MensagemRecebida}. */
    private record Entrada(
            String mid,
            Perfil remetente,
            String ts,
            MensagemChat.Tipo tipo,
            String conteudo,
            boolean ilegivel,
            boolean cifradoNoTransito) {}

    private final String login;
    private final Path arquivo;
    private final Chave chaveHistorico;
    private final Chave chaveTransito;
    private final Map<String, Registro> registros;
    private long fimValido;

    private HistoricoLocal(
            String login,
            Path arquivo,
            Chave chaveHistorico,
            Chave chaveTransito,
            Map<String, Registro> registros,
            long fimValido) {
        this.login = login;
        this.arquivo = arquivo;
        this.chaveHistorico = chaveHistorico;
        this.chaveTransito = chaveTransito;
        this.registros = registros;
        this.fimValido = fimValido;
    }

    // ------------------------------------------------------------------ abertura

    /** {@code <diretorioDesktop>/<login>}, por exemplo {@code <raiz>/.comunicador/desktop/ana}. */
    public static Path diretorioHistorico(Path diretorioDesktop, String login) throws ErroHistorico {
        return diretorioDesktop.resolve(validarLogin(login));
    }

    /**
     * Abre (criando, se preciso) o Historico_Local do usuario neste dispositivo.
     *
     * @param diretorioDesktop {@code <raiz>/.comunicador/desktop} (ver {@code ConfigCliente.diretorioDesktop()})
     * @param login login da conta ({@code ^[a-z0-9_]{3,20}$})
     * @param chaveTransito usada para decifrar o que chega do servidor e para garantir que a
     *     Chave_Historico gerada seja diferente dela
     */
    public static HistoricoLocal abrir(Path diretorioDesktop, String login, Chave chaveTransito)
            throws ErroHistorico {
        if (diretorioDesktop == null || chaveTransito == null) {
            throw new ErroHistorico("Parametros do Historico_Local ausentes");
        }
        Path diretorio = diretorioHistorico(diretorioDesktop, login);
        try {
            criarDiretorioPrivado(diretorio);
        } catch (IOException e) {
            throw new ErroHistorico("Falha ao abrir o Historico_Local", e);
        }
        Chave chave = garantirChave(diretorio.resolve(NOME_ARQUIVO_CHAVE), chaveTransito);
        Path arquivo = diretorio.resolve(NOME_ARQUIVO_HISTORICO);
        Map<String, Registro> registros = new LinkedHashMap<>();
        long fim = carregarArquivo(arquivo, registros);
        return new HistoricoLocal(login, arquivo, chave, chaveTransito, registros, fim);
    }

    private static String validarLogin(String login) throws ErroHistorico {
        if (login == null || !REGEX_LOGIN.matcher(login).matches()) {
            throw new ErroHistorico("Login invalido para o Historico_Local");
        }
        return login;
    }

    private static void criarDiretorioPrivado(Path diretorio) throws IOException {
        Files.createDirectories(diretorio);
        try {
            Files.setPosixFilePermissions(diretorio, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            // sistema sem permissoes POSIX
        }
    }

    // ------------------------------------------------------------------ Chave_Historico

    private static Chave lerChaveValida(Path arquivo) {
        try {
            String texto = Files.readString(arquivo, StandardCharsets.US_ASCII).strip();
            return REGEX_CHAVE_HEX.matcher(texto).matches() ? Chave.deHex(texto) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Le (ou cria, na primeira execucao) a Chave_Historico.
     *
     * <p>A criacao e atomica e exclusiva (arquivo temporario + hard link), para que dois processos
     * abrindo ao mesmo tempo nao fiquem com chaves diferentes e ninguem leia um arquivo pela
     * metade. Um conteudo invalido e substituido.
     */
    private static Chave garantirChave(Path arquivo, Chave chaveTransito) throws ErroHistorico {
        Chave existente = lerChaveValida(arquivo);
        if (existente != null) {
            return existente;
        }

        Chave nova = Chave.gerar();
        while (nova.equals(chaveTransito)) { // deve diferir da Chave_Transito
            nova = Chave.gerar();
        }

        byte[] sufixo = new byte[4];
        ALEATORIO.nextBytes(sufixo);
        Path temporario =
                arquivo.resolveSibling(
                        arquivo.getFileName() + "." + ProcessHandle.current().pid() + "."
                                + java.util.HexFormat.of().formatHex(sufixo) + ".tmp");
        try {
            Files.writeString(
                    temporario,
                    nova.paraHex() + "\n",
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(temporario, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException e) {
                // sistema sem permissoes POSIX
            }
            try {
                if (Files.exists(arquivo)) {
                    // arquivo presente mas invalido: substitui
                    Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    try {
                        Files.createLink(arquivo, temporario); // falha se outro processo criou antes
                    } catch (FileAlreadyExistsException e) {
                        if (lerChaveValida(arquivo) == null) {
                            Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (UnsupportedOperationException | IOException e) {
                        // sistema de arquivos sem hard link
                        Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } finally {
                try {
                    Files.deleteIfExists(temporario);
                } catch (IOException e) {
                    // melhor esforco
                }
            }
        } catch (IOException e) {
            throw new ErroHistorico("Nao foi possivel gravar a Chave_Historico", e);
        }

        Chave final_ = lerChaveValida(arquivo);
        if (final_ == null) {
            throw new ErroHistorico("Nao foi possivel gravar a Chave_Historico");
        }
        return final_;
    }

    // ------------------------------------------------------------------ leitura do arquivo

    /**
     * Le o historico para {@code destino}. Devolve o deslocamento logo depois do ultimo {@code \n};
     * o que vem depois e uma linha incompleta, ignorada. Linhas ilegiveis ou invalidas sao
     * ignoradas; em {@code mid} repetido vale a primeira.
     */
    private static long carregarArquivo(Path arquivo, Map<String, Registro> destino) throws ErroHistorico {
        byte[] dados;
        try {
            dados = Files.readAllBytes(arquivo);
        } catch (java.nio.file.NoSuchFileException e) {
            return 0;
        } catch (IOException e) {
            throw new ErroHistorico("Falha ao ler o Historico_Local", e);
        }

        int fim = dados.length;
        while (fim > 0 && dados[fim - 1] != '\n') {
            fim--;
        }

        int inicio = 0;
        for (int i = 0; i < fim; i++) {
            if (dados[i] != '\n') {
                continue;
            }
            Registro r = lerLinha(dados, inicio, i - inicio);
            if (r != null) {
                destino.putIfAbsent(r.mid(), r);
            }
            inicio = i + 1;
        }
        return fim;
    }

    private static Registro lerLinha(byte[] dados, int inicio, int tamanho) {
        if (new String(dados, inicio, tamanho, StandardCharsets.UTF_8).isBlank()) {
            return null;
        }
        try {
            JsonNode no = MAPPER.readTree(dados, inicio, tamanho);
            if (no == null || !no.isObject()) {
                return null;
            }
            String mid = textoNaoVazio(no.get("mid"));
            String ts = textoNaoVazio(no.get("ts"));
            MensagemChat.Tipo tipo = no.hasNonNull("tipo") && no.get("tipo").isTextual()
                    ? MensagemChat.Tipo.deValor(no.get("tipo").textValue())
                    : null;
            JsonNode rem = no.get("remetente");
            JsonNode conteudo = no.get("conteudo");
            if (mid == null || ts == null || tipo == null || rem == null || !rem.isObject()
                    || conteudo == null || !conteudo.isTextual()) {
                return null;
            }
            if (!rem.path("login").isTextual() || !rem.path("apelido").isTextual()
                    || !rem.path("avatar").isTextual() || !rem.path("cor").isTextual()) {
                return null;
            }
            Perfil perfil = new Perfil(
                    rem.get("login").textValue(),
                    rem.get("apelido").textValue(),
                    rem.get("avatar").textValue(),
                    rem.get("cor").textValue());
            return new Registro(mid, perfil, ts, tipo, conteudo.textValue());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String textoNaoVazio(JsonNode no) {
        return no != null && no.isTextual() && !no.textValue().isEmpty() ? no.textValue() : null;
    }

    // ------------------------------------------------------------------ incorporacao

    /**
     * Incorpora mensagens como chegam no quadro: {@code conteudo} cifrado com a Chave_Transito.
     * Se nao decifrar, a mensagem e contada em {@code ilegiveis} e nao e gravada.
     *
     * @see #incorporarRecebidas(Collection)
     */
    public ResultadoIncorporacao incorporar(Collection<MensagemChat> recebidas) throws ErroHistorico {
        List<Entrada> entradas = new ArrayList<>();
        if (recebidas != null) {
            for (MensagemChat m : recebidas) {
                entradas.add(
                        m == null
                                ? null
                                : new Entrada(m.mid(), m.remetente(), m.ts(), m.tipo(), m.conteudo(), false, true));
            }
        }
        return incorporarEntradas(entradas);
    }

    /**
     * Incorpora mensagens que a {@code ConexaoTcp} ja decifrou com a Chave_Transito. Mensagens
     * marcadas como ilegiveis, ou sem texto, sao contadas em {@code ilegiveis} e nao sao gravadas.
     */
    public ResultadoIncorporacao incorporarRecebidas(Collection<MensagemRecebida> recebidas)
            throws ErroHistorico {
        List<Entrada> entradas = new ArrayList<>();
        if (recebidas != null) {
            for (MensagemRecebida m : recebidas) {
                entradas.add(
                        m == null
                                ? null
                                : new Entrada(
                                        m.mid(), m.remetente(), m.ts(), m.tipo(), m.conteudo(), m.ilegivel(), false));
            }
        }
        return incorporarEntradas(entradas);
    }

    private synchronized ResultadoIncorporacao incorporarEntradas(List<Entrada> entradas)
            throws ErroHistorico {
        int existentes = 0;
        int ilegiveis = 0;
        int invalidas = 0;
        List<Registro> novos = new ArrayList<>();
        Set<String> vistos = new HashSet<>();

        for (Entrada e : entradas) {
            if (e == null) {
                invalidas++;
                continue;
            }
            if (e.ilegivel()) {
                ilegiveis++;
                continue;
            }
            if (e.mid() == null || e.mid().isEmpty() || e.ts() == null || e.ts().isEmpty()
                    || e.tipo() == null || e.remetente() == null || e.conteudo() == null) {
                invalidas++;
                continue;
            }
            if (registros.containsKey(e.mid()) || vistos.contains(e.mid())) {
                existentes++; // duplicata: vale a primeira
                continue;
            }
            String claro = e.conteudo();
            if (e.cifradoNoTransito()) {
                try {
                    claro = CifraManual.decifrar(chaveTransito, claro);
                } catch (DecifragemFalhouException ex) {
                    ilegiveis++;
                    continue;
                }
            }
            vistos.add(e.mid());
            novos.add(new Registro(
                    e.mid(), e.remetente(), e.ts(), e.tipo(), CifraManual.cifrar(chaveHistorico, claro)));
        }

        if (!novos.isEmpty()) {
            anexar(novos);
            for (Registro r : novos) {
                registros.put(r.mid(), r);
            }
        }
        return new ResultadoIncorporacao(novos.size(), existentes, ilegiveis, invalidas);
    }

    /** Anexa os registros em uma unica escrita; se falhar, nada e adicionado ao mapa. */
    private void anexar(List<Registro> novos) throws ErroHistorico {
        StringBuilder sb = new StringBuilder();
        for (Registro r : novos) {
            ObjectNode no = MAPPER.createObjectNode();
            no.put("mid", r.mid());
            ObjectNode rem = no.putObject("remetente");
            rem.put("login", r.remetente().login());
            rem.put("apelido", r.remetente().apelido());
            rem.put("avatar", r.remetente().avatar());
            rem.put("cor", r.remetente().cor());
            no.put("ts", r.ts());
            no.put("tipo", r.tipo().valor());
            no.put("conteudo", r.conteudo());
            try {
                sb.append(MAPPER.writeValueAsString(no)).append('\n');
            } catch (IOException e) {
                throw new ErroHistorico("Falha ao gravar o Historico_Local", e);
            }
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.US_ASCII);

        try {
            criarDiretorioPrivado(arquivo.getParent());
            boolean novoArquivo = !Files.exists(arquivo);
            try (FileChannel canal =
                    FileChannel.open(arquivo, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                if (canal.size() > fimValido) {
                    // descarta a linha incompleta deixada por uma queda anterior
                    canal.truncate(fimValido);
                }
                canal.position(fimValido);
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    canal.write(buf);
                }
                canal.force(true);
                fimValido = canal.position();
            }
            if (novoArquivo) {
                try {
                    Files.setPosixFilePermissions(arquivo, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException | IOException e) {
                    // sistema sem permissoes POSIX
                }
            }
        } catch (IOException e) {
            throw new ErroHistorico("Falha ao gravar o Historico_Local", e);
        }
    }

    // ------------------------------------------------------------------ consulta

    /**
     * Mensagens decifradas, ordenadas por {@code (ts, mid)}. Registro que nao decifra vem com
     * {@code conteudo == null} e {@code ilegivel == true}.
     */
    public List<MensagemRecebida> listar() {
        List<MensagemRecebida> saida = new ArrayList<>();
        for (Registro r : registrosCifrados()) {
            String conteudo = null;
            boolean ilegivel = false;
            try {
                conteudo = CifraManual.decifrar(chaveHistorico, r.conteudo());
            } catch (DecifragemFalhouException e) {
                ilegivel = true;
            }
            saida.add(new MensagemRecebida(r.mid(), r.remetente(), r.ts(), r.tipo(), conteudo, ilegivel));
        }
        return saida;
    }

    /** Registros como gravados ({@code conteudo} cifrado), ordenados por {@code (ts, mid)}. */
    public synchronized List<Registro> registrosCifrados() {
        List<Registro> copia = new ArrayList<>(registros.values());
        copia.sort(POR_TS_MID);
        return copia;
    }

    /** Conjunto de {@code mid} guardados. */
    public synchronized Set<String> mids() {
        return Set.copyOf(registros.keySet());
    }

    public synchronized boolean contem(String mid) {
        return registros.containsKey(mid);
    }

    public synchronized int contar() {
        return registros.size();
    }

    public String login() {
        return login;
    }

    /** Caminho de {@code historico.txt}. */
    public Path caminho() {
        return arquivo;
    }

    /** Nada fica aberto entre operacoes; existe por simetria com os outros Clientes. */
    @Override
    public void close() {}
}
