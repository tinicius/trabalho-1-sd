package br.cefet.sd.middleware.repositorio;

import br.cefet.sd.comum.protocolo.MensagemChat;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Repositorio_Conversas em arquivo ({@code dados/conversas.txt}): um objeto JSON por linha com
 * {@code mid}, {@code remetente}, {@code ts}, {@code tipo} e {@code conteudo}.
 *
 * <p>O {@code conteudo} chega e e gravado exatamente como recebido: a cifragem com a
 * Chave_Repositorio e feita por quem monta o {@link RegistroConversa}. As escritas sao
 * serializadas pelo lock do {@link RepositorioArquivo}.
 */
public final class RepositorioConversasArquivo implements RepositorioConversas {

    /** Nome do arquivo dentro do diretorio de dados. */
    public static final String NOME_ARQUIVO = "conversas.txt";

    private final RepositorioArquivo arquivo;

    public RepositorioConversasArquivo(RepositorioArquivo arquivo) {
        this.arquivo = Objects.requireNonNull(arquivo, "arquivo");
    }

    /**
     * Abre {@code <dadosDir>/conversas.txt}, criando o diretorio se faltar e removendo o
     * {@code .tmp} residual de uma reescrita interrompida.
     *
     * @throws IOException se o diretorio nao pode ser criado
     */
    public static RepositorioConversasArquivo emDiretorio(Path dadosDir) throws IOException {
        return new RepositorioConversasArquivo(new RepositorioArquivo(dadosDir.resolve(NOME_ARQUIVO)));
    }

    /** O {@link RepositorioArquivo} subjacente (usado pela Limpeza, que compartilha o lock). */
    public RepositorioArquivo arquivo() {
        return arquivo;
    }

    @Override
    public void anexar(RegistroConversa registro) throws IOException {
        arquivo.anexar(serializar(registro));
    }

    @Override
    public List<RegistroConversa> janela(Instant desde) throws IOException {
        Objects.requireNonNull(desde, "desde");
        List<RegistroConversa> resultado = new ArrayList<>();
        for (String linha : arquivo.lerLinhas()) {
            RegistroConversa registro;
            try {
                registro = desserializar(linha);
            } catch (IllegalArgumentException ilegivel) {
                continue; // linha ilegivel: ignorada (a Limpeza a conta e descarta)
            }
            if (!registro.ts().isBefore(desde)) {
                resultado.add(registro);
            }
        }
        return resultado;
    }

    /**
     * Linha JSON do registro, com os campos na ordem {@code mid, remetente, ts, tipo, conteudo}.
     */
    public static String serializar(RegistroConversa registro) {
        ObjectNode no = RegistroJson.novo();
        no.put("mid", registro.mid());
        no.put("remetente", registro.remetente());
        no.put("ts", registro.tsTexto());
        no.put("tipo", registro.tipo().valor());
        no.put("conteudo", registro.conteudo());
        return RegistroJson.serializar(no);
    }

    /**
     * Interpreta uma linha do arquivo.
     *
     * @throws IllegalArgumentException se a linha nao e JSON valido ou falta/esta invalido algum
     *     dos cinco campos
     */
    public static RegistroConversa desserializar(String linha) {
        ObjectNode no = RegistroJson.lerObjeto(linha);
        MensagemChat.Tipo tipo = MensagemChat.Tipo.deValor(RegistroJson.textoObrigatorio(no, "tipo"));
        if (tipo == null) {
            throw new IllegalArgumentException("Campo invalido: tipo");
        }
        return new RegistroConversa(
                RegistroJson.textoObrigatorio(no, "mid"),
                RegistroJson.textoObrigatorio(no, "remetente"),
                RegistroJson.lerTs(RegistroJson.textoObrigatorio(no, "ts")),
                tipo,
                RegistroJson.textoObrigatorio(no, "conteudo"));
    }
}
