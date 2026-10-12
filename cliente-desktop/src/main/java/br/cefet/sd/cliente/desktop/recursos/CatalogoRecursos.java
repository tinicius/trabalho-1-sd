package br.cefet.sd.cliente.desktop.recursos;

import br.cefet.sd.comum.emoticons.TabelaConversao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Recursos compartilhados carregados da pasta {@code recursos/} (fonte unica dos tres Clientes):
 * Tabela_de_Conversao de emoticons, lista de emojis do Seletor_de_Emojis, PNG das Figurinhas e dos
 * avatares.
 *
 * <p>A leitura de imagens usa {@link ImageIO}, que funciona sem display (modo headless).
 */
public final class CatalogoRecursos {

    /** Quantidade de emojis do Seletor_de_Emojis (6 linhas x 8 colunas). */
    public static final int QUANTIDADE_EMOJIS = 48;

    /** Variavel de ambiente que sobrescreve o local da pasta {@code recursos/}. */
    public static final String VARIAVEL_RECURSOS = "COMUNICADOR_RECURSOS";

    private static final Pattern ID_FIGURINHA = Pattern.compile("fig_[0-9]{2}");
    private static final Pattern ID_AVATAR = Pattern.compile("av_[0-9]{2}");
    private static final ObjectMapper MAPEADOR = new ObjectMapper();

    private final TabelaConversao emoticons;
    private final List<String> emojis;
    private final Map<String, BufferedImage> figurinhas;
    private final Map<String, BufferedImage> avatares;

    private CatalogoRecursos(
            TabelaConversao emoticons,
            List<String> emojis,
            Map<String, BufferedImage> figurinhas,
            Map<String, BufferedImage> avatares) {
        this.emoticons = emoticons;
        this.emojis = emojis;
        this.figurinhas = figurinhas;
        this.avatares = avatares;
    }

    /**
     * Carrega todos os recursos da pasta indicada.
     *
     * @throws IOException se algum arquivo estiver ausente, ilegivel ou invalido
     */
    public static CatalogoRecursos carregar(Path raiz) throws IOException {
        TabelaConversao tabela;
        try {
            tabela = TabelaConversao.carregar(raiz.resolve("emoticons.json"));
        } catch (IllegalArgumentException e) {
            throw new IOException("emoticons.json invalido", e);
        }
        List<String> emojis = carregarEmojis(raiz.resolve("emojis.json"));
        Map<String, BufferedImage> figurinhas =
                carregarPng(raiz.resolve("figurinhas"), ID_FIGURINHA);
        Map<String, BufferedImage> avatares = carregarPng(raiz.resolve("avatares"), ID_AVATAR);
        return new CatalogoRecursos(tabela, emojis, figurinhas, avatares);
    }

    /**
     * Localiza a pasta {@code recursos/}: variavel de ambiente {@value #VARIAVEL_RECURSOS}; se
     * ausente, sobe a partir do diretorio de trabalho ate achar {@code recursos/emojis.json}.
     */
    public static Optional<Path> localizarRaiz() {
        String ambiente = System.getenv(VARIAVEL_RECURSOS);
        if (ambiente != null && !ambiente.isBlank()) {
            Path p = Path.of(ambiente);
            return Files.isRegularFile(p.resolve("emojis.json")) ? Optional.of(p) : Optional.empty();
        }
        Path atual = Path.of("").toAbsolutePath();
        while (atual != null) {
            Path candidato = atual.resolve("recursos");
            if (Files.isRegularFile(candidato.resolve("emojis.json"))) {
                return Optional.of(candidato);
            }
            atual = atual.getParent();
        }
        return Optional.empty();
    }

    private static List<String> carregarEmojis(Path arquivo) throws IOException {
        JsonNode raiz = MAPEADOR.readTree(Files.readString(arquivo, StandardCharsets.UTF_8));
        if (raiz == null || !raiz.isArray()) {
            throw new IOException("emojis.json deve ser uma lista JSON");
        }
        List<String> lista = new ArrayList<>();
        for (JsonNode no : raiz) {
            if (!no.isTextual() || no.asText().isEmpty()) {
                throw new IOException("emojis.json contem item invalido");
            }
            lista.add(no.asText());
        }
        if (lista.size() != QUANTIDADE_EMOJIS) {
            throw new IOException(
                    "emojis.json deve ter " + QUANTIDADE_EMOJIS + " emojis, tem " + lista.size());
        }
        return Collections.unmodifiableList(lista);
    }

    private static Map<String, BufferedImage> carregarPng(Path pasta, Pattern padraoId)
            throws IOException {
        if (!Files.isDirectory(pasta)) {
            throw new IOException("Pasta de imagens ausente: " + pasta.getFileName());
        }
        Map<String, BufferedImage> mapa = new TreeMap<>();
        List<Path> arquivos;
        try (Stream<Path> fluxo = Files.list(pasta)) {
            arquivos = fluxo.sorted().toList();
        }
        for (Path arquivo : arquivos) {
            String nome = arquivo.getFileName().toString();
            if (!nome.endsWith(".png")) {
                continue;
            }
            String id = nome.substring(0, nome.length() - ".png".length());
            if (!padraoId.matcher(id).matches()) {
                continue;
            }
            BufferedImage imagem = ImageIO.read(arquivo.toFile());
            if (imagem == null) {
                throw new IOException("PNG ilegivel: " + nome);
            }
            mapa.put(id, imagem);
        }
        if (mapa.isEmpty()) {
            throw new IOException("Nenhuma imagem em " + pasta.getFileName());
        }
        return Collections.unmodifiableMap(mapa);
    }

    /** Tabela_de_Conversao de emoticons para emojis. */
    public TabelaConversao emoticons() {
        return emoticons;
    }

    /** Os 48 emojis do Seletor_de_Emojis, na ordem fixa do arquivo (linha por linha). */
    public List<String> emojis() {
        return emojis;
    }

    /** Figurinhas por identificador ({@code fig_NN}), em ordem crescente. */
    public Map<String, BufferedImage> figurinhas() {
        return figurinhas;
    }

    /** Avatares por identificador ({@code av_NN}), em ordem crescente. */
    public Map<String, BufferedImage> avatares() {
        return avatares;
    }

    /** Imagem da Figurinha, ou vazio se o identificador for desconhecido. */
    public Optional<BufferedImage> figurinha(String id) {
        return Optional.ofNullable(figurinhas.get(id));
    }

    /** Imagem do avatar, ou vazio se o identificador for desconhecido. */
    public Optional<BufferedImage> avatar(String id) {
        return Optional.ofNullable(avatares.get(id));
    }
}
