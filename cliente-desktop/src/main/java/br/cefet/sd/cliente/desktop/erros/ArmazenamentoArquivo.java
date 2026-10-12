package br.cefet.sd.cliente.desktop.erros;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Armazenamento em arquivo ({@code <raiz>/.comunicador/desktop/erros_pendentes.txt}), no mesmo formato de
 * {@code cliente-python/comunicador/nucleo/erros.py}: opcionalmente uma linha {@code
 * {"descartados":N}} (sem {@code evid}) no inicio e depois um evento JSON por linha. Linhas
 * ilegiveis ou invalidas sao ignoradas.
 *
 * <p>A gravacao e atomica (arquivo temporario + {@code move}); o diretorio fica com permissao 0700 e
 * o arquivo com 0600 (quando o sistema oferece permissoes POSIX).
 */
public final class ArmazenamentoArquivo implements ArmazenamentoErros {

    /** Nome do arquivo do buffer dentro de {@code <raiz>/.comunicador/desktop}. */
    public static final String NOME_ARQUIVO_ERROS = "erros_pendentes.txt";

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final Path caminho;

    public ArmazenamentoArquivo(Path caminho) {
        this.caminho = caminho;
    }

    /** Armazenamento em {@code diretorioDesktop/erros_pendentes.txt}. */
    public static ArmazenamentoArquivo em(Path diretorioDesktop) {
        return new ArmazenamentoArquivo(diretorioDesktop.resolve(NOME_ARQUIVO_ERROS));
    }

    public Path caminho() {
        return caminho;
    }

    @Override
    public synchronized Estado carregar() {
        String texto;
        try {
            texto = new String(Files.readAllBytes(caminho), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Estado.vazio();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<EventoPendente> eventos = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        int descartados = 0;
        for (String bruta : texto.split("\n")) {
            String linha = bruta.strip();
            if (linha.isEmpty()) {
                continue;
            }
            JsonNode no = EventosJson.ler(linha);
            if (no == null || !no.isObject()) {
                continue; // linha ilegivel ou incompleta
            }
            if (!no.has("evid")) {
                JsonNode valor = no.get("descartados");
                if (valor != null && valor.isIntegralNumber() && valor.canConvertToInt() && valor.asInt() > 0) {
                    descartados = valor.asInt();
                }
                continue;
            }
            EventoPendente evento = comoEvento(no);
            if (evento == null || !vistos.add(evento.evid())) {
                continue;
            }
            eventos.add(evento);
        }
        return new Estado(eventos, descartados);
    }

    private static EventoPendente comoEvento(JsonNode no) {
        String evid = texto(no, "evid");
        String ts = texto(no, "ts");
        String modulo = texto(no, "modulo");
        String tipo = texto(no, "tipo");
        String descricao = texto(no, "descricao");
        String pilha = texto(no, "pilha");
        if (evid == null || evid.isEmpty() || ts == null || modulo == null || tipo == null
                || descricao == null || pilha == null) {
            return null;
        }
        JsonNode t = no.get("tentativas");
        int tentativas = t != null && t.isIntegralNumber() && t.canConvertToInt() && t.asInt() >= 0 ? t.asInt() : 0;
        String cliente = texto(no, "cliente");
        return new EventoPendente(
                evid, ts, modulo, tipo, descricao, pilha, texto(no, "dispositivo"),
                cliente == null ? "desktop" : cliente, tentativas);
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        return v != null && v.isTextual() ? v.textValue() : null;
    }

    @Override
    public synchronized void gravar(Estado estado) {
        try {
            Path diretorio = caminho.toAbsolutePath().getParent();
            Files.createDirectories(diretorio);
            if (POSIX) {
                try {
                    Files.setPosixFilePermissions(diretorio, PosixFilePermissions.fromString("rwx------"));
                } catch (UnsupportedOperationException | IOException e) {
                    // sem permissoes POSIX
                }
            }
            StringBuilder conteudo = new StringBuilder();
            if (estado.descartados() > 0) {
                ObjectNode contador = JsonNodeFactory.instance.objectNode();
                contador.put("descartados", estado.descartados());
                conteudo.append(EventosJson.texto(contador)).append('\n');
            }
            for (EventoPendente evento : estado.eventos()) {
                conteudo.append(EventosJson.texto(EventosJson.paraArquivo(evento))).append('\n');
            }

            Path temporario = caminho.resolveSibling(caminho.getFileName() + ".tmp");
            Files.deleteIfExists(temporario);
            if (POSIX) {
                FileAttribute<?> privado =
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
                Files.createFile(temporario, privado);
            }
            Files.write(
                    temporario,
                    conteudo.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.SYNC);
            try {
                Files.move(temporario, caminho, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporario, caminho, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
