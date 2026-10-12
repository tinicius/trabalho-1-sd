package br.cefet.sd.middleware.logs;

import br.cefet.sd.middleware.repositorio.RegistroJson;
import br.cefet.sd.middleware.repositorio.RepositorioArquivo;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Log_Envios em arquivo ({@code dados/logs/envios.txt}): cada linha tem exatamente {@code ts} e
 * {@code login}, sem mid, tipo, texto, figurinha, emoji nem conteudo cifrado.
 */
public final class LogEnviosArquivo implements LogEnvios {

    /** Caminho do arquivo relativo ao diretorio de dados. */
    public static final String CAMINHO_RELATIVO = "logs/envios.txt";

    private final RepositorioArquivo arquivo;

    public LogEnviosArquivo(RepositorioArquivo arquivo) {
        this.arquivo = Objects.requireNonNull(arquivo, "arquivo");
    }

    /**
     * Abre {@code <dadosDir>/logs/envios.txt}, criando os diretorios se faltarem.
     *
     * @throws IOException se o diretorio nao pode ser criado
     */
    public static LogEnviosArquivo emDiretorio(Path dadosDir) throws IOException {
        return new LogEnviosArquivo(new RepositorioArquivo(dadosDir.resolve(CAMINHO_RELATIVO)));
    }

    /** O {@link RepositorioArquivo} subjacente (usado pela Limpeza, que compartilha o lock). */
    public RepositorioArquivo arquivo() {
        return arquivo;
    }

    @Override
    public void registrar(Instant ts, String login) throws IOException {
        arquivo.anexar(serializar(new RegistroEnvio(ts, login)));
    }

    /** Linha JSON {@code {"ts":...,"login":...}}. */
    public static String serializar(RegistroEnvio registro) {
        ObjectNode no = RegistroJson.novo();
        no.put("ts", RegistroJson.tsParaTexto(registro.ts()));
        no.put("login", registro.login());
        return RegistroJson.serializar(no);
    }

    /**
     * Interpreta uma linha do arquivo.
     *
     * @throws IllegalArgumentException se a linha nao e JSON valido ou {@code ts}/{@code login}
     *     faltam ou sao invalidos
     */
    public static RegistroEnvio desserializar(String linha) {
        ObjectNode no = RegistroJson.lerObjeto(linha);
        return new RegistroEnvio(
                RegistroJson.lerTs(RegistroJson.textoObrigatorio(no, "ts")),
                RegistroJson.textoObrigatorio(no, "login"));
    }
}
