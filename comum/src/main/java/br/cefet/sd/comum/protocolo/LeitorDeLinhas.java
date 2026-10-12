package br.cefet.sd.comum.protocolo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Separa um fluxo de bytes em linhas terminadas em {@code \n} (transporte TCP do Protocolo).
 *
 * <p>Comportamento identico ao {@code LeitorDeLinhas} de {@code protocolo.py}:
 *
 * <ul>
 *   <li>aceita particao arbitraria do fluxo (inclusive um byte por chamada); o resultado nao depende
 *       de como os bytes foram particionados;
 *   <li>remove um {@code \r} final da linha;
 *   <li>linha vazia e entregue como {@link Linha} de conteudo vazio;
 *   <li>o limite (padrao 1 MiB) conta os bytes antes do {@code \n}, incluindo o {@code \r}. Quando a
 *       linha passa do limite, emite uma unica {@link Linha} com {@link Linha#muitoGrande()} assim que
 *       o excesso e detectado (o Middleware responde {@code MENSAGEM_MUITO_GRANDE}), descarta os bytes
 *       ate o proximo {@code \n} e segue normalmente na linha seguinte.
 * </ul>
 *
 * <p>Nao e thread-safe: uma instancia por conexao, usada pela thread de leitura.
 */
public final class LeitorDeLinhas {

    /** Tamanho maximo de uma linha, em bytes (1 MiB), sem contar o {@code \n}. */
    public static final int LIMITE_LINHA = 1_048_576;

    private static final byte DELIMITADOR = '\n';
    private static final byte RETORNO = '\r';

    /**
     * Item produzido pelo leitor.
     *
     * @param conteudo bytes da linha sem o {@code \n} e sem o {@code \r} final (vazio se a linha era
     *     vazia ou se {@code muitoGrande})
     * @param muitoGrande verdadeiro quando a linha excedeu o limite e foi descartada
     */
    public record Linha(byte[] conteudo, boolean muitoGrande) {

        private static final byte[] VAZIO = new byte[0];

        static Linha normal(byte[] conteudo) {
            return new Linha(conteudo, false);
        }

        static Linha excedeu() {
            return new Linha(VAZIO, true);
        }

        /**
         * Conteudo decodificado em UTF-8 estrito.
         *
         * @throws ErroDeProtocoloException {@code FORMATO_INVALIDO} se os bytes nao forem UTF-8 valido
         */
        public String texto() throws ErroDeProtocoloException {
            return ProtocoloJson.decodificarUtf8(conteudo);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Linha outra
                    && muitoGrande == outra.muitoGrande
                    && Arrays.equals(conteudo, outra.conteudo);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(conteudo) + Boolean.hashCode(muitoGrande);
        }

        @Override
        public String toString() {
            return "Linha[" + conteudo.length + " bytes, muitoGrande=" + muitoGrande + "]";
        }
    }

    private final int limite;
    private byte[] buffer = new byte[256];
    private int tamanho;
    private boolean descartando;

    public LeitorDeLinhas() {
        this(LIMITE_LINHA);
    }

    /**
     * @param limite maximo de bytes de uma linha antes do {@code \n} (incluindo o {@code \r})
     * @throws IllegalArgumentException se {@code limite} nao for positivo
     */
    public LeitorDeLinhas(int limite) {
        if (limite <= 0) {
            throw new IllegalArgumentException("limite deve ser positivo");
        }
        this.limite = limite;
    }

    public int limite() {
        return limite;
    }

    /** Bytes de uma linha ainda incompleta mantidos em memoria. */
    public int bytesPendentes() {
        return tamanho;
    }

    /** Consome {@code dados} e devolve as linhas completadas, em ordem. */
    public List<Linha> alimentar(byte[] dados) {
        return alimentar(dados, 0, dados.length);
    }

    /** Consome {@code dados[inicio, inicio+quantidade)} e devolve as linhas completadas, em ordem. */
    public List<Linha> alimentar(byte[] dados, int inicio, int quantidade) {
        if (inicio < 0 || quantidade < 0 || inicio > dados.length - quantidade) {
            throw new IndexOutOfBoundsException("intervalo fora do vetor");
        }
        List<Linha> saida = new ArrayList<>();
        int pos = inicio;
        int total = inicio + quantidade;
        while (pos < total) {
            int indice = indiceDoDelimitador(dados, pos, total);
            int fim = indice == -1 ? total : indice;

            if (!descartando) {
                int trecho = fim - pos;
                if ((long) tamanho + trecho > limite) {
                    saida.add(Linha.excedeu());
                    tamanho = 0;
                    descartando = true;
                } else {
                    anexar(dados, pos, trecho);
                }
            }

            if (indice == -1) {
                break;
            }

            if (descartando) {
                descartando = false;
            } else {
                int fimLinha = tamanho;
                if (fimLinha > 0 && buffer[fimLinha - 1] == RETORNO) {
                    fimLinha--;
                }
                saida.add(Linha.normal(Arrays.copyOf(buffer, fimLinha)));
                tamanho = 0;
            }
            pos = indice + 1;
        }
        return saida;
    }

    private static int indiceDoDelimitador(byte[] dados, int de, int ate) {
        for (int i = de; i < ate; i++) {
            if (dados[i] == DELIMITADOR) {
                return i;
            }
        }
        return -1;
    }

    private void anexar(byte[] dados, int inicio, int quantidade) {
        if (quantidade == 0) {
            return;
        }
        int necessario = tamanho + quantidade;
        if (necessario > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(necessario, buffer.length * 2));
        }
        System.arraycopy(dados, inicio, buffer, tamanho, quantidade);
        tamanho = necessario;
    }
}
