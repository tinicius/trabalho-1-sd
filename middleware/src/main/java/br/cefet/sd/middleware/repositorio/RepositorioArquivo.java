package br.cefet.sd.middleware.repositorio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * Arquivo de texto com um registro JSON por linha. Cada instancia cuida de um
 * arquivo e tem o seu proprio {@link ReentrantLock}, que serializa {@link #anexar},
 * {@link #lerLinhas} e {@link #reescrever}; a Limpeza usa o mesmo lock.
 *
 * <p>Cada anexacao abre o arquivo em modo de anexacao, grava o registro inteiro (com o
 * {@code \n} final) em uma unica escrita e fecha o arquivo. Assim, nenhuma anexacao mantem um
 * descritor para o arquivo antigo depois do {@code rename} feito por {@link #reescrever}.
 */
public final class RepositorioArquivo {

    private static final String SUFIXO_TMP = ".tmp";

    private final Path arquivo;
    private final Path temporario;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Cria o repositorio, o diretorio pai se faltar e remove o {@code <arquivo>.tmp} residual de
     * uma reescrita interrompida (o arquivo original permanece integro nesse caso).
     *
     * @throws IOException se o diretorio nao pode ser criado ou o residual nao pode ser removido
     */
    public RepositorioArquivo(Path arquivo) throws IOException {
        this.arquivo = Objects.requireNonNull(arquivo, "arquivo").toAbsolutePath();
        Path pai = this.arquivo.getParent();
        this.temporario = this.arquivo.resolveSibling(this.arquivo.getFileName() + SUFIXO_TMP);
        if (pai != null) {
            Files.createDirectories(pai);
        }
        Files.deleteIfExists(temporario);
    }

    /** Caminho do arquivo gerenciado. */
    public Path caminho() {
        return arquivo;
    }

    /**
     * Acrescenta o registro como uma linha propria.
     *
     * @param linhaJson registro em uma unica linha, sem {@code \n} nem {@code \r}
     * @throws IllegalArgumentException se o registro e vazio ou contem quebra de linha
     * @throws IOException se a gravacao falha
     */
    public void anexar(String linhaJson) throws IOException {
        Objects.requireNonNull(linhaJson, "linhaJson");
        if (linhaJson.isEmpty() || linhaJson.indexOf('\n') >= 0 || linhaJson.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("O registro deve ocupar exatamente uma linha nao vazia");
        }
        ByteBuffer dados = ByteBuffer.wrap((linhaJson + "\n").getBytes(StandardCharsets.UTF_8));
        lock.lock();
        try (FileChannel canal = FileChannel.open(arquivo,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (dados.hasRemaining()) {
                canal.write(dados);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Linhas do arquivo, em ordem de gravacao, sem as linhas vazias. Arquivo inexistente equivale
     * a arquivo vazio.
     *
     * @throws IOException se a leitura falha
     */
    public List<String> lerLinhas() throws IOException {
        lock.lock();
        try {
            return lerSemLock();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reescreve o arquivo de forma atomica: sob o lock, le as linhas, aplica o filtro, grava o
     * resultado em {@code <arquivo>.tmp} com {@code force()} e troca o arquivo com
     * {@code ATOMIC_MOVE}. Se qualquer passo falhar, o arquivo original fica intacto.
     *
     * @param filtro recebe uma copia modificavel das linhas atuais e devolve as linhas a manter
     * @throws IOException se a leitura, a gravacao ou a troca falha
     */
    public void reescrever(UnaryOperator<List<String>> filtro) throws IOException {
        Objects.requireNonNull(filtro, "filtro");
        lock.lock();
        try {
            List<String> mantidas = filtro.apply(new ArrayList<>(lerSemLock()));
            Objects.requireNonNull(mantidas, "o filtro nao pode devolver null");
            try {
                gravarTemporario(mantidas);
                Files.move(temporario, arquivo,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                try {
                    Files.deleteIfExists(temporario);
                } catch (IOException suprimida) {
                    e.addSuppressed(suprimida);
                }
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private List<String> lerSemLock() throws IOException {
        if (!Files.exists(arquivo)) {
            return new ArrayList<>();
        }
        String texto = new String(Files.readAllBytes(arquivo), StandardCharsets.UTF_8);
        List<String> linhas = new ArrayList<>();
        int inicio = 0;
        while (inicio < texto.length()) {
            int fim = texto.indexOf('\n', inicio);
            if (fim < 0) {
                fim = texto.length();
            }
            int fimUtil = fim > inicio && texto.charAt(fim - 1) == '\r' ? fim - 1 : fim;
            if (fimUtil > inicio) {
                linhas.add(texto.substring(inicio, fimUtil));
            }
            inicio = fim + 1;
        }
        return linhas;
    }

    private void gravarTemporario(List<String> linhas) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String linha : linhas) {
            sb.append(linha).append('\n');
        }
        ByteBuffer dados = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
        try (FileChannel canal = FileChannel.open(temporario, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            while (dados.hasRemaining()) {
                canal.write(dados);
            }
            canal.force(true);
        }
    }
}
