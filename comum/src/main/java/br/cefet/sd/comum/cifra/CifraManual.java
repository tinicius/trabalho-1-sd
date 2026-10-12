package br.cefet.sd.comum.cifra;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Cifra_Manual: cifra de fluxo com MAC manual (encrypt-then-MAC).
 *
 * <p>Formato dos bytes: {@code versao(1) || nonce(8) || texto cifrado(n) || tag(8)}, codificado
 * em Base64 padrao apenas como transporte textual. Toda a aritmetica e de inteiros de 32 bits
 * ({@code int} do Java: soma, XOR, {@link Integer#rotateLeft} e produtos modulo 2^32). Nenhuma
 * biblioteca de cifragem ou hash e usada; as unicas funcoes prontas sao UTF-8, Base64 e
 * {@link SecureRandom} (nonce).
 *
 * <p>Limitacoes (didatico, nao usar em producao): o gerador e um PRNG comum, o MAC e caseiro e de
 * 64 bits, o tamanho do texto vaza e nao ha troca de chaves.
 */
public final class CifraManual {

    private static final int VERSAO = 0x01;
    private static final int TAMANHO_NONCE = 8;
    private static final int TAMANHO_TAG = 8;
    private static final int TAMANHO_MINIMO = 1 + TAMANHO_NONCE + TAMANHO_TAG;

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private CifraManual() {}

    // ---------------------------------------------------------------- API publica

    /** Cifra o texto com um nonce aleatorio de 8 bytes novo a cada chamada. */
    public static String cifrar(Chave k, String texto) {
        byte[] nonce = new byte[TAMANHO_NONCE];
        ALEATORIO.nextBytes(nonce);
        return cifrarComNonce(k, nonce, texto);
    }

    /** Mesma funcao de {@link #cifrar}, com nonce fornecido. */
    public static String cifrarComNonce(Chave k, byte[] nonce, String texto) {
        if (k == null) {
            throw new IllegalArgumentException("Chave ausente");
        }
        if (nonce == null || nonce.length != TAMANHO_NONCE) {
            throw new IllegalArgumentException("Nonce invalido: esperados " + TAMANHO_NONCE + " bytes");
        }
        if (texto == null) {
            throw new IllegalArgumentException("Texto ausente");
        }
        byte[] chave = k.bytesInternos();
        byte[] pt = paraUtf8(texto);
        byte[] ks = keystream(chave, nonce, pt.length);
        byte[] ct = new byte[pt.length];
        for (int i = 0; i < pt.length; i++) {
            ct[i] = (byte) (pt[i] ^ ks[i]);
        }
        byte[] tag = mac(chave, nonce, ct);

        byte[] saida = new byte[1 + TAMANHO_NONCE + ct.length + TAMANHO_TAG];
        saida[0] = VERSAO;
        System.arraycopy(nonce, 0, saida, 1, TAMANHO_NONCE);
        System.arraycopy(ct, 0, saida, 1 + TAMANHO_NONCE, ct.length);
        System.arraycopy(tag, 0, saida, 1 + TAMANHO_NONCE + ct.length, TAMANHO_TAG);
        return Base64.getEncoder().encodeToString(saida);
    }

    /**
     * Decifra um Base64 produzido por {@link #cifrar}.
     *
     * @throws DecifragemFalhouException Base64 invalido, tamanho/versao invalidos, MAC que nao
     *     confere ou UTF-8 invalido
     */
    public static String decifrar(Chave k, String b64) throws DecifragemFalhouException {
        if (k == null) {
            throw new IllegalArgumentException("Chave ausente");
        }
        byte[] bruto = base64Estrito(b64);
        if (bruto.length < TAMANHO_MINIMO) {
            throw new DecifragemFalhouException("Dado cifrado curto demais");
        }
        if ((bruto[0] & 0xFF) != VERSAO) {
            throw new DecifragemFalhouException("Versao de cifra desconhecida");
        }
        byte[] chave = k.bytesInternos();
        byte[] nonce = Arrays.copyOfRange(bruto, 1, 1 + TAMANHO_NONCE);
        int tamanhoCt = bruto.length - TAMANHO_MINIMO;
        byte[] ct = Arrays.copyOfRange(bruto, 1 + TAMANHO_NONCE, 1 + TAMANHO_NONCE + tamanhoCt);
        byte[] tag = Arrays.copyOfRange(bruto, bruto.length - TAMANHO_TAG, bruto.length);

        byte[] esperada = mac(chave, nonce, ct);
        int diferenca = 0; // comparacao em tempo constante
        for (int i = 0; i < TAMANHO_TAG; i++) {
            diferenca |= esperada[i] ^ tag[i];
        }
        if (diferenca != 0) {
            throw new DecifragemFalhouException("Autenticacao falhou");
        }

        byte[] ks = keystream(chave, nonce, ct.length);
        byte[] pt = new byte[ct.length];
        for (int i = 0; i < ct.length; i++) {
            pt[i] = (byte) (ct[i] ^ ks[i]);
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(pt))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new DecifragemFalhouException("Texto nao e UTF-8 valido", e);
        }
    }

    // ---------------------------------------------------------------- primitivas

    /** Passo A: mistura bytes no estado de quatro palavras. */
    private static void absorve(int[] h, byte[] dados) {
        for (int i = 0; i < dados.length; i++) {
            int j = i & 3;
            int x = h[j] ^ (dados[i] & 0xFF);
            x = Integer.rotateLeft(x, 5);
            x = x * 0x9E3779B1;
            h[j] = x;
            int k = (j + 1) & 3;
            h[k] = h[k] + (x >>> 13);
        }
    }

    /** Passo B: difusao final, 8 rodadas. */
    private static void mistura(int[] h) {
        for (int r = 0; r < 8; r++) {
            for (int j = 0; j < 4; j++) {
                int x = h[j] ^ (h[(j + 3) & 3] >>> 16);
                x = x * 0x85EBCA6B;
                x = Integer.rotateLeft(x, 13) ^ h[(j + 1) & 3];
                h[j] = x;
            }
        }
    }

    /** Passo C: keystream de {@code tamanho} bytes (saidas de 32 bits em little-endian). */
    private static byte[] keystream(byte[] chave, byte[] nonce, int tamanho) {
        int[] h = {0x243F6A88, 0x85A308D3, 0x13198A2E, 0x03707344};
        absorve(h, concat(chave, nonce));
        mistura(h);
        int[] s = h;
        if (s[0] == 0 && s[1] == 0 && s[2] == 0 && s[3] == 0) {
            s[0] = 1;
        }
        for (int i = 0; i < 8; i++) {
            proximo(s);
        }
        byte[] ks = new byte[tamanho];
        int pos = 0;
        while (pos < tamanho) {
            int r = proximo(s);
            for (int b = 0; b < 4 && pos < tamanho; b++) {
                ks[pos++] = (byte) (r >>> (8 * b));
            }
        }
        return ks;
    }

    /** Estrutura xorshift com saida misturada. */
    private static int proximo(int[] s) {
        int r = Integer.rotateLeft(s[1] * 5, 7) * 9;
        int t = s[1] << 9;
        s[2] ^= s[0];
        s[3] ^= s[1];
        s[1] ^= s[2];
        s[0] ^= s[3];
        s[2] ^= t;
        s[3] = Integer.rotateLeft(s[3], 11);
        return r;
    }

    /** Passo D: MAC de 8 bytes sobre versao, nonce e texto cifrado. */
    private static byte[] mac(byte[] chave, byte[] nonce, byte[] ct) {
        int[] h = {0x452821E6, 0x38D01377, 0xBE5466CF, 0x34E90C6C};
        absorve(h, concat(new byte[] {0x4D}, chave));
        byte[] cabecalho = new byte[1 + TAMANHO_NONCE];
        cabecalho[0] = VERSAO;
        System.arraycopy(nonce, 0, cabecalho, 1, TAMANHO_NONCE);
        absorve(h, concat(cabecalho, ct));
        absorve(
                h,
                new byte[] {
                    (byte) (ct.length >>> 24), (byte) (ct.length >>> 16),
                    (byte) (ct.length >>> 8), (byte) ct.length
                });
        mistura(h);
        int a = h[0] ^ h[2];
        int b = h[1] ^ h[3];
        return new byte[] {
            (byte) (a >>> 24), (byte) (a >>> 16), (byte) (a >>> 8), (byte) a,
            (byte) (b >>> 24), (byte) (b >>> 16), (byte) (b >>> 8), (byte) b
        };
    }

    // ---------------------------------------------------------------- utilitarios

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** UTF-8 estrito na entrada: surrogates solitarios nao sao trocados por '?'. */
    private static byte[] paraUtf8(String texto) {
        try {
            ByteBuffer bb =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(texto));
            byte[] r = new byte[bb.remaining()];
            bb.get(r);
            return r;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Texto com sequencia Unicode invalida", e);
        }
    }

    /**
     * Base64 estrito: alfabeto padrao, preenchimento '=' obrigatorio, sem espacos nem quebras e
     * sem bits residuais diferentes de zero. Verificado recodificando e comparando com a entrada.
     */
    private static byte[] base64Estrito(String b64) throws DecifragemFalhouException {
        if (b64 == null) {
            throw new DecifragemFalhouException("Base64 invalido");
        }
        try {
            byte[] bruto = Base64.getDecoder().decode(b64);
            if (!Base64.getEncoder().encodeToString(bruto).equals(b64)) {
                throw new DecifragemFalhouException("Base64 invalido");
            }
            return bruto;
        } catch (IllegalArgumentException e) {
            throw new DecifragemFalhouException("Base64 invalido", e);
        }
    }
}
