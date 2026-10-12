package br.cefet.sd.middleware.logs;

import br.cefet.sd.middleware.repositorio.RegistroJson;
import br.cefet.sd.middleware.repositorio.RepositorioArquivo;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Log_Acessos em arquivo ({@code dados/logs/acessos.txt}): um objeto JSON por linha com
 * {@code ts, evento, login, sessao, dispositivo, cliente, endereco} e, so em {@code desconexao},
 * {@code motivo}. Campos sem valor saem como {@code null}. O registro nunca contem senha; a
 * regra de gravar {@code login} em {@code login_recusado} so se o valor casar com
 * {@code ^[a-z0-9_]{3,20}$} e do chamador.
 */
public final class LogAcessosArquivo implements LogAcessos {

    /** Caminho do arquivo relativo ao diretorio de dados. */
    public static final String CAMINHO_RELATIVO = "logs/acessos.txt";

    private final RepositorioArquivo arquivo;

    public LogAcessosArquivo(RepositorioArquivo arquivo) {
        this.arquivo = Objects.requireNonNull(arquivo, "arquivo");
    }

    /**
     * Abre {@code <dadosDir>/logs/acessos.txt}, criando os diretorios se faltarem.
     *
     * @throws IOException se o diretorio nao pode ser criado
     */
    public static LogAcessosArquivo emDiretorio(Path dadosDir) throws IOException {
        return new LogAcessosArquivo(new RepositorioArquivo(dadosDir.resolve(CAMINHO_RELATIVO)));
    }

    /** O {@link RepositorioArquivo} subjacente (usado pela Limpeza, que compartilha o lock). */
    public RepositorioArquivo arquivo() {
        return arquivo;
    }

    @Override
    public void registrar(EventoAcesso evento) throws IOException {
        arquivo.anexar(serializar(evento));
    }

    /**
     * Linha JSON do evento. {@code ts} sai truncado para milissegundos; {@code motivo} so e
     * escrito quando nao e {@code null}.
     */
    public static String serializar(EventoAcesso evento) {
        ObjectNode no = RegistroJson.novo();
        no.put("ts", RegistroJson.tsParaTexto(evento.ts()));
        no.put("evento", evento.evento().valor());
        no.put("login", evento.login());
        no.put("sessao", evento.sessao());
        no.put("dispositivo", evento.dispositivo());
        no.put("cliente", evento.cliente());
        no.put("endereco", evento.endereco());
        if (evento.motivo() != null) {
            no.put("motivo", evento.motivo());
        }
        return RegistroJson.serializar(no);
    }

    /**
     * Interpreta uma linha do arquivo. O {@code ts} devolvido tem precisao de milissegundos.
     *
     * @throws IllegalArgumentException se a linha nao e JSON valido ou {@code ts}/{@code evento}
     *     faltam ou sao invalidos
     */
    public static EventoAcesso desserializar(String linha) {
        ObjectNode no = RegistroJson.lerObjeto(linha);
        String valorEvento = RegistroJson.textoObrigatorio(no, "evento");
        EventoAcesso.Evento evento = null;
        for (EventoAcesso.Evento candidato : EventoAcesso.Evento.values()) {
            if (candidato.valor().equals(valorEvento)) {
                evento = candidato;
                break;
            }
        }
        if (evento == null) {
            throw new IllegalArgumentException("Campo invalido: evento");
        }
        Instant ts = RegistroJson.lerTs(RegistroJson.textoObrigatorio(no, "ts")).truncatedTo(ChronoUnit.MILLIS);
        return new EventoAcesso(
                ts,
                evento,
                RegistroJson.textoOuNulo(no, "login"),
                RegistroJson.textoOuNulo(no, "sessao"),
                RegistroJson.textoOuNulo(no, "dispositivo"),
                RegistroJson.textoOuNulo(no, "cliente"),
                RegistroJson.textoOuNulo(no, "endereco"),
                RegistroJson.textoOuNulo(no, "motivo"));
    }
}
