package br.cefet.sd.comum.cifra;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Chave_de_Canal da Cifra_Manual: exatamente 32 bytes.
 *
 * <p>A representacao de configuracao sao 64 caracteres hexadecimais (0-9, a-f, A-F).
 * O objeto e imutavel e {@link #toString()} nunca revela o material da chave.
 */
public final class Chave {

    /** Tamanho da chave em bytes. */
    public static final int TAMANHO_BYTES = 32;

    /** Tamanho da representacao hexadecimal. */
    public static final int TAMANHO_HEX = TAMANHO_BYTES * 2;

    private final byte[] bytes;

    private Chave(byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * Cria uma chave a partir de 64 caracteres hexadecimais.
     *
     * @throws IllegalArgumentException se o texto for nulo, nao tiver 64 caracteres ou contiver
     *     caractere fora de [0-9a-fA-F] (a mensagem nao ecoa o valor recebido)
     */
    public static Chave deHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("Chave ausente: esperados 64 caracteres hexadecimais");
        }
        if (hex.length() != TAMANHO_HEX) {
            throw new IllegalArgumentException(
                    "Chave invalida: esperados " + TAMANHO_HEX + " caracteres hexadecimais");
        }
        byte[] saida = new byte[TAMANHO_BYTES];
        for (int i = 0; i < TAMANHO_BYTES; i++) {
            int alto = valorHex(hex.charAt(2 * i));
            int baixo = valorHex(hex.charAt(2 * i + 1));
            if (alto < 0 || baixo < 0) {
                throw new IllegalArgumentException("Chave invalida: caractere nao hexadecimal");
            }
            saida[i] = (byte) ((alto << 4) | baixo);
        }
        return new Chave(saida);
    }

    /**
     * Cria uma chave a partir de 32 bytes (copiados).
     *
     * @throws IllegalArgumentException se o vetor for nulo ou nao tiver 32 bytes
     */
    public static Chave deBytes(byte[] bytes) {
        if (bytes == null || bytes.length != TAMANHO_BYTES) {
            throw new IllegalArgumentException(
                    "Chave invalida: esperados " + TAMANHO_BYTES + " bytes");
        }
        return new Chave(bytes.clone());
    }

    /** Gera uma chave aleatoria de 32 bytes com {@link SecureRandom}. */
    public static Chave gerar() {
        byte[] b = new byte[TAMANHO_BYTES];
        new SecureRandom().nextBytes(b);
        return new Chave(b);
    }

    /** Copia dos 32 bytes da chave. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** A chave em 64 caracteres hexadecimais minusculos. */
    public String paraHex() {
        char[] digitos = "0123456789abcdef".toCharArray();
        char[] saida = new char[TAMANHO_HEX];
        for (int i = 0; i < TAMANHO_BYTES; i++) {
            saida[2 * i] = digitos[(bytes[i] >> 4) & 0xF];
            saida[2 * i + 1] = digitos[bytes[i] & 0xF];
        }
        return new String(saida);
    }

    /** Acesso interno (sem copia) para a cifra. */
    byte[] bytesInternos() {
        return bytes;
    }

    private static int valorHex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Chave outra && Arrays.equals(bytes, outra.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "Chave[***]";
    }
}
