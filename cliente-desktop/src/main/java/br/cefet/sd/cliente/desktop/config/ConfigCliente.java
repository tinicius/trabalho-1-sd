package br.cefet.sd.cliente.desktop.config;

import br.cefet.sd.comum.cifra.Chave;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;

/**
 * Configuracao local do Cliente_Desktop.
 *
 * <ul>
 *   <li>Chave_Transito: lida de {@code <raiz>/.comunicador/chave_transito.txt} (64 caracteres
 *       hexadecimais), entregue manualmente fora do socket. Se o arquivo estiver ausente ou for
 *       invalido, o login fica impedido e {@link #orientacaoChave()} explica o que fazer.
 *   <li>Identificador de dispositivo: UUID v4 em {@code <raiz>/.comunicador/desktop/dispositivo.id},
 *       gerado na primeira execucao e reutilizado depois.
 * </ul>
 *
 * <p>A classe recebe o diretorio base do usuario, o que permite testar sem tocar no {@code ~} real.
 */
public final class ConfigCliente {

    /** Nome do diretorio de configuracao dentro do diretorio do usuario. */
    public static final String DIRETORIO_COMUNICADOR = ".comunicador";

    /** Nome do arquivo da Chave_Transito. */
    public static final String ARQUIVO_CHAVE_TRANSITO = "chave_transito.txt";

    /** Nome do subdiretorio do Cliente_Desktop. */
    public static final String SUBDIRETORIO_DESKTOP = "desktop";

    /** Nome do arquivo do identificador de dispositivo. */
    public static final String ARQUIVO_DISPOSITIVO = "dispositivo.id";

    private final Path diretorioComunicador;
    private final Path arquivoChaveTransito;
    private final Path arquivoDispositivo;
    private final Chave chaveTransito;
    private final String orientacaoChave;
    private final String dispositivo;

    private ConfigCliente(
            Path diretorioComunicador,
            Chave chaveTransito,
            String orientacaoChave,
            String dispositivo) {
        this.diretorioComunicador = diretorioComunicador;
        this.arquivoChaveTransito = diretorioComunicador.resolve(ARQUIVO_CHAVE_TRANSITO);
        this.arquivoDispositivo =
                diretorioComunicador.resolve(SUBDIRETORIO_DESKTOP).resolve(ARQUIVO_DISPOSITIVO);
        this.chaveTransito = chaveTransito;
        this.orientacaoChave = orientacaoChave;
        this.dispositivo = dispositivo;
    }

    /**
     * Carrega a configuracao a partir da raiz do projeto, que e o diretorio de trabalho atual
     * ({@code user.dir}). O aplicativo deve ser executado a partir da raiz do projeto, o mesmo
     * requisito da pasta {@code recursos/}.
     */
    public static ConfigCliente carregar() throws IOException {
        return carregar(Path.of(System.getProperty("user.dir")));
    }

    /**
     * Carrega a configuracao a partir de um diretorio base de usuario.
     *
     * <p>A ausencia ou invalidez da Chave_Transito nao lanca excecao: ela e registrada e consultada
     * por {@link #loginPermitido()} e {@link #orientacaoChave()}. O identificador de dispositivo e
     * criado se necessario, o que pode falhar com {@link IOException}.
     */
    public static ConfigCliente carregar(Path homeUsuario) throws IOException {
        Path base = homeUsuario.resolve(DIRETORIO_COMUNICADOR);
        Path arquivoChave = base.resolve(ARQUIVO_CHAVE_TRANSITO);

        Chave chave = null;
        String orientacao = null;
        if (!Files.isRegularFile(arquivoChave)) {
            orientacao =
                    "Chave de transito nao encontrada. Copie o arquivo chave_transito.txt, "
                            + "entregue pelo administrador do servidor, para "
                            + arquivoChave
                            + " e abra o aplicativo novamente.";
        } else {
            try {
                String texto = Files.readString(arquivoChave, StandardCharsets.UTF_8);
                chave = Chave.deHex(limpar(texto));
            } catch (IllegalArgumentException | IOException e) {
                // A mensagem da excecao nao e repassada: ela poderia descrever o conteudo da chave.
                orientacao =
                        "A chave de transito em "
                                + arquivoChave
                                + " e invalida. O arquivo deve conter somente 64 caracteres "
                                + "hexadecimais (0-9, a-f). Peca ao administrador uma nova copia "
                                + "do arquivo e substitua o atual.";
            }
        }

        String idDispositivo = lerOuCriarDispositivo(base.resolve(SUBDIRETORIO_DESKTOP)
                .resolve(ARQUIVO_DISPOSITIVO));
        return new ConfigCliente(base, chave, orientacao, idDispositivo);
    }

    /** Remove espacos, quebras de linha e o BOM UTF-8 que editores de texto costumam inserir. */
    private static String limpar(String texto) {
        String t = texto;
        if (!t.isEmpty() && t.charAt(0) == '\uFEFF') {
            t = t.substring(1);
        }
        return t.strip();
    }

    private static String lerOuCriarDispositivo(Path arquivo) throws IOException {
        if (Files.isRegularFile(arquivo)) {
            try {
                String existente = Files.readString(arquivo, StandardCharsets.UTF_8).strip();
                return UUID.fromString(existente).toString();
            } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException e) {
                // Arquivo corrompido: um novo identificador e gerado abaixo.
            }
        }
        String novo = UUID.randomUUID().toString();
        Files.createDirectories(arquivo.getParent());
        Files.writeString(
                arquivo,
                novo + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return novo;
    }

    /** {@code true} se a Chave_Transito foi lida e e valida; sem ela o login fica impedido. */
    public boolean loginPermitido() {
        return chaveTransito != null;
    }

    /** A Chave_Transito, se valida. */
    public Optional<Chave> chaveTransito() {
        return Optional.ofNullable(chaveTransito);
    }

    /**
     * A Chave_Transito para uso na cifragem.
     *
     * @throws ConfigClienteException com a orientacao ao usuario se a chave estiver ausente ou
     *     invalida
     */
    public Chave exigirChaveTransito() {
        if (chaveTransito == null) {
            throw new ConfigClienteException(orientacaoChave);
        }
        return chaveTransito;
    }

    /** Texto de orientacao ao usuario quando a chave esta ausente ou invalida; vazio se esta ok. */
    public Optional<String> orientacaoChave() {
        return Optional.ofNullable(orientacaoChave);
    }

    /** Identificador de dispositivo (UUID v4 em texto), enviado no {@code login}. */
    public String dispositivo() {
        return dispositivo;
    }

    /** Diretorio {@code <raiz>/.comunicador}. */
    public Path diretorioComunicador() {
        return diretorioComunicador;
    }

    /** Diretorio {@code <raiz>/.comunicador/desktop}, base do historico e dos buffers locais. */
    public Path diretorioDesktop() {
        return diretorioComunicador.resolve(SUBDIRETORIO_DESKTOP);
    }

    /** Caminho esperado do arquivo da Chave_Transito. */
    public Path arquivoChaveTransito() {
        return arquivoChaveTransito;
    }

    /** Caminho do arquivo do identificador de dispositivo. */
    public Path arquivoDispositivo() {
        return arquivoDispositivo;
    }

    @Override
    public String toString() {
        return "ConfigCliente[dispositivo=" + dispositivo + ", loginPermitido=" + loginPermitido()
                + "]";
    }
}
