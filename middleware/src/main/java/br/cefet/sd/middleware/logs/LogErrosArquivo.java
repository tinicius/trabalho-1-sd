package br.cefet.sd.middleware.logs;

import br.cefet.sd.comum.mascaramento.Mascaramento;
import br.cefet.sd.middleware.repositorio.RegistroJson;
import br.cefet.sd.middleware.repositorio.RepositorioArquivo;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Log_Erros em arquivo ({@code dados/logs/erros.txt}): um objeto JSON por linha com {@code ts,
 * origem, modulo, tipo, descricao, pilha, sessao, dispositivo, cliente, evid, descartados}.
 *
 * <p>Antes de gravar, {@link #registrar} aplica o {@link Mascaramento} a {@code descricao} e
 * {@code pilha} e so depois trunca em 2000 e 8000 caracteres: truncar antes
 * poderia cortar um segredo ao meio e deixar um resto que o mascaramento nao reconhece. Campos
 * sem valor saem como {@code null}.
 */
public final class LogErrosArquivo implements LogErros {

    /** Caminho do arquivo relativo ao diretorio de dados. */
    public static final String CAMINHO_RELATIVO = "logs/erros.txt";

    /** Tamanho maximo de {@code descricao}. */
    public static final int MAXIMO_DESCRICAO = 2000;

    /** Tamanho maximo de {@code pilha}. */
    public static final int MAXIMO_PILHA = 8000;

    private final RepositorioArquivo arquivo;

    public LogErrosArquivo(RepositorioArquivo arquivo) {
        this.arquivo = Objects.requireNonNull(arquivo, "arquivo");
    }

    /**
     * Abre {@code <dadosDir>/logs/erros.txt}, criando os diretorios se faltarem.
     *
     * @throws IOException se o diretorio nao pode ser criado
     */
    public static LogErrosArquivo emDiretorio(Path dadosDir) throws IOException {
        return new LogErrosArquivo(new RepositorioArquivo(dadosDir.resolve(CAMINHO_RELATIVO)));
    }

    /** O {@link RepositorioArquivo} subjacente (usado pela Limpeza, que compartilha o lock). */
    public RepositorioArquivo arquivo() {
        return arquivo;
    }

    /**
     * Grava o evento com {@code descricao} e {@code pilha} mascaradas e truncadas. Nao lanca
     * excecao: se algo falhar, relata o tipo e a mensagem da falha em {@code stderr}, sem gerar
     * novo registro no Log_Erros e sem repetir o conteudo do evento.
     */
    @Override
    public void registrar(EventoErro evento) {
        try {
            arquivo.anexar(serializar(protegido(evento)));
        } catch (Exception falha) {
            System.err.println("Falha ao gravar no Log_Erros: " + falha.getClass().getName()
                    + (falha.getMessage() == null ? "" : ": " + falha.getMessage()));
        }
    }

    /** Copia do evento com {@code descricao} e {@code pilha} mascaradas e depois truncadas. */
    static EventoErro protegido(EventoErro e) {
        return new EventoErro(
                e.ts(),
                e.origem(),
                e.modulo(),
                e.tipo(),
                RegistroJson.truncar(Mascaramento.mascarar(e.descricao()), MAXIMO_DESCRICAO),
                RegistroJson.truncar(Mascaramento.mascarar(e.pilha()), MAXIMO_PILHA),
                e.sessao(),
                e.dispositivo(),
                e.cliente(),
                e.evid(),
                e.descartados());
    }

    /**
     * Linha JSON do evento, exatamente como dado: sem mascarar nem truncar (isso e feito por
     * {@link #registrar}). {@code ts} sai truncado para milissegundos.
     */
    public static String serializar(EventoErro evento) {
        ObjectNode no = RegistroJson.novo();
        no.put("ts", RegistroJson.tsParaTexto(evento.ts()));
        no.put("origem", evento.origem().valor());
        no.put("modulo", evento.modulo());
        no.put("tipo", evento.tipo());
        no.put("descricao", evento.descricao());
        no.put("pilha", evento.pilha());
        no.put("sessao", evento.sessao());
        no.put("dispositivo", evento.dispositivo());
        no.put("cliente", evento.cliente());
        no.put("evid", evento.evid());
        no.put("descartados", evento.descartados());
        return RegistroJson.serializar(no);
    }

    /**
     * Interpreta uma linha do arquivo. O {@code ts} devolvido tem precisao de milissegundos.
     *
     * @throws IllegalArgumentException se a linha nao e JSON valido ou algum campo obrigatorio
     *     ({@code ts, origem, modulo, tipo, descricao, pilha, descartados}) falta ou e invalido
     */
    public static EventoErro desserializar(String linha) {
        ObjectNode no = RegistroJson.lerObjeto(linha);
        String valorOrigem = RegistroJson.textoObrigatorio(no, "origem");
        EventoErro.Origem origem = null;
        for (EventoErro.Origem candidata : EventoErro.Origem.values()) {
            if (candidata.valor().equals(valorOrigem)) {
                origem = candidata;
                break;
            }
        }
        if (origem == null) {
            throw new IllegalArgumentException("Campo invalido: origem");
        }
        Instant ts = RegistroJson.lerTs(RegistroJson.textoObrigatorio(no, "ts")).truncatedTo(ChronoUnit.MILLIS);
        return new EventoErro(
                ts,
                origem,
                RegistroJson.textoObrigatorio(no, "modulo"),
                RegistroJson.textoObrigatorio(no, "tipo"),
                RegistroJson.textoObrigatorio(no, "descricao"),
                RegistroJson.textoObrigatorio(no, "pilha"),
                RegistroJson.textoOuNulo(no, "sessao"),
                RegistroJson.textoOuNulo(no, "dispositivo"),
                RegistroJson.textoOuNulo(no, "cliente"),
                RegistroJson.textoOuNulo(no, "evid"),
                RegistroJson.inteiroNaoNegativo(no, "descartados"));
    }
}
