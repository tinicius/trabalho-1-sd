package br.cefet.sd.middleware.contas;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Tratamento_de_Senhas: armazenamento e verificacao de senhas de Contas.
 *
 * <p>E independente da Cifra_Manual: a senha nao e cifrada para guardar, e transformada por um
 * hash unidirecional com salt. Usa SHA-256 ({@link MessageDigest}, funcao de hash pronta), iterado.
 *
 * <h2>Formato armazenado</h2>
 *
 * <pre>sha256i$&lt;N&gt;$&lt;salt hex&gt;$&lt;hash hex&gt;</pre>
 *
 * Exemplo: {@code sha256i$100000$00112233445566778899aabbccddeeff$<64 caracteres hex>}.
 * <ul>
 *   <li>{@code sha256i}: marcador fixo do algoritmo (SHA-256 iterado).</li>
 *   <li>{@code N}: numero de iteracoes em decimal, sem sinal nem zeros a esquerda (N &gt;= 1). Fica
 *       no proprio valor, entao a verificacao nao depende da configuracao atual.</li>
 *   <li>{@code salt}: 16 bytes aleatorios por conta, codificados em hexadecimal minusculo (32
 *       caracteres). O salt e usado como bytes brutos no calculo, nunca como o texto hexa.</li>
 *   <li>{@code hash}: 32 bytes (saida do SHA-256) em hexadecimal minusculo (64 caracteres).</li>
 * </ul>
 * O separador e o caractere {@code $}; nenhum dos quatro campos o contem.
 *
 * <h2>Calculo exato do hash (para implementacoes em outras linguagens)</h2>
 *
 * Sejam {@code salt} os 16 bytes brutos e {@code senha_utf8} a senha codificada em UTF-8 (sem
 * normalizacao Unicode, sem BOM, sem terminador). {@code ||} e concatenacao de bytes.
 *
 * <pre>
 * h = SHA256(salt || senha_utf8)                  // iteracao 1
 * repita N-1 vezes:
 *     h = SHA256(h || salt || senha_utf8)         // h = 32 bytes da iteracao anterior
 * hash = hex_minusculo(h)                         // 64 caracteres
 * </pre>
 *
 * Ou seja, para N = 1 o hash e apenas {@code SHA256(salt || senha_utf8)}. Em Python:
 *
 * <pre>
 * import hashlib
 * def hash_senha(senha, salt, n):
 *     s = senha.encode("utf-8")
 *     h = hashlib.sha256(salt + s).digest()
 *     for _ in range(n - 1):
 *         h = hashlib.sha256(h + salt + s).digest()
 *     return f"sha256i${n}${salt.hex()}${h.hex()}"
 * </pre>
 *
 * <h2>Seguranca</h2>
 * A comparacao e feita em tempo constante ({@link MessageDigest#isEqual}). Para login inexistente,
 * {@link #verificarContaInexistente(String)} faz o mesmo trabalho de uma verificacao real, de modo
 * que o tempo de resposta nao revele quais logins existem. SHA-256 iterado e mais fraco que bcrypt,
 * scrypt ou Argon2, ausentes da biblioteca padrao do Java; a escolha e didatica. Nenhuma excecao
 * desta classe inclui a senha em sua descricao. Instancias sao imutaveis e seguras entre threads.
 */
public final class TratamentoSenhas {

    /** Marcador do algoritmo no valor armazenado. */
    public static final String PREFIXO = "sha256i";

    /** Iteracoes padrao para novas contas. */
    public static final int ITERACOES_PADRAO = 100_000;

    /** Tamanho do salt em bytes. */
    public static final int TAMANHO_SALT = 16;

    /** Tamanho da saida do SHA-256 em bytes. */
    public static final int TAMANHO_HASH = 32;

    /** Limite superior aceito para N ao verificar, para um valor adulterado nao travar o servidor. */
    private static final int ITERACOES_MAXIMAS = 100_000_000;

    private static final String ALGORITMO = "SHA-256";
    private static final char SEPARADOR = '$';
    private static final HexFormat HEX = HexFormat.of();

    /** Salt fixo usado so no hash fictico de conta inexistente (nao e segredo). */
    private static final byte[] SALT_FICTICIO = new byte[TAMANHO_SALT];

    /** Hash fixo (todo zero) contra o qual o resultado fictico e comparado; nunca casa na pratica. */
    private static final byte[] HASH_FICTICIO = new byte[TAMANHO_HASH];

    private final int iteracoes;
    private final SecureRandom aleatorio;

    /** Usa {@link #ITERACOES_PADRAO} e um {@link SecureRandom} novo. */
    public TratamentoSenhas() {
        this(ITERACOES_PADRAO);
    }

    /**
     * @param iteracoes iteracoes usadas ao criar novos valores (configuravel, vide
     *     {@code senha.iteracoes}; os testes usam um valor reduzido)
     * @throws IllegalArgumentException se {@code iteracoes < 1}
     */
    public TratamentoSenhas(int iteracoes) {
        this(iteracoes, new SecureRandom());
    }

    /**
     * @param iteracoes iteracoes usadas ao criar novos valores
     * @param aleatorio fonte de bytes aleatorios para o salt
     * @throws IllegalArgumentException se {@code iteracoes < 1} ou {@code aleatorio} e nulo
     */
    public TratamentoSenhas(int iteracoes, SecureRandom aleatorio) {
        if (iteracoes < 1 || iteracoes > ITERACOES_MAXIMAS) {
            throw new IllegalArgumentException("iteracoes fora do intervalo permitido");
        }
        if (aleatorio == null) {
            throw new IllegalArgumentException("fonte aleatoria ausente");
        }
        this.iteracoes = iteracoes;
        this.aleatorio = aleatorio;
    }

    /** Iteracoes usadas para novos valores. */
    public int iteracoes() {
        return iteracoes;
    }

    /**
     * Gera o valor a armazenar em {@code contas.senha_hash}, com um salt novo de 16 bytes aleatorios.
     *
     * @return {@code sha256i$N$salt$hash}
     */
    public String gerar(String senha) {
        byte[] salt = new byte[TAMANHO_SALT];
        aleatorio.nextBytes(salt);
        return gerar(senha, salt);
    }

    /**
     * Gera o valor a armazenar com o salt informado (determinista).
     *
     * @param salt exatamente 16 bytes
     * @return {@code sha256i$N$salt$hash}
     */
    public String gerar(String senha, byte[] salt) {
        if (senha == null) {
            throw new IllegalArgumentException("senha ausente");
        }
        if (salt == null || salt.length != TAMANHO_SALT) {
            throw new IllegalArgumentException("salt deve ter " + TAMANHO_SALT + " bytes");
        }
        byte[] hash = calcular(senha, salt, iteracoes);
        return PREFIXO + SEPARADOR + iteracoes + SEPARADOR
                + HEX.formatHex(salt) + SEPARADOR + HEX.formatHex(hash);
    }

    /**
     * Verifica a senha contra o valor armazenado, usando o N e o salt do proprio valor e comparando
     * em tempo constante.
     *
     * @return {@code true} se a senha corresponde; {@code false} se nao corresponde ou se o valor
     *     armazenado esta malformado (nunca lanca por causa do conteudo de {@code armazenado})
     */
    public boolean verificar(String senha, String armazenado) {
        if (senha == null || armazenado == null) {
            return false;
        }
        Decomposto d = decompor(armazenado);
        if (d == null) {
            return false;
        }
        byte[] calculado = calcular(senha, d.salt, d.iteracoes);
        return MessageDigest.isEqual(calculado, d.hash);
    }

    /**
     * Para login inexistente: calcula o hash da senha informada contra um salt fixo, com as
     * iteracoes configuradas, e o compara em tempo constante com um valor fixo, para que o tempo de
     * resposta seja equivalente ao de {@link #verificar}. O chamador responde
     * {@code CREDENCIAIS_INVALIDAS} nos dois casos.
     *
     * @return sempre {@code false} na pratica (so seria {@code true} se o SHA-256 produzisse 32
     *     bytes zero)
     */
    public boolean verificarContaInexistente(String senha) {
        byte[] calculado = calcular(senha == null ? "" : senha, SALT_FICTICIO, iteracoes);
        return MessageDigest.isEqual(calculado, HASH_FICTICIO);
    }

    /** Hash bruto (32 bytes) conforme descrito na documentacao da classe. */
    private static byte[] calcular(String senha, byte[] salt, int n) {
        MessageDigest sha = novoDigest();
        byte[] s = senha.getBytes(StandardCharsets.UTF_8);
        sha.update(salt);
        sha.update(s);
        byte[] h = sha.digest();
        for (int i = 1; i < n; i++) {
            sha.update(h);
            sha.update(salt);
            sha.update(s);
            h = sha.digest();
        }
        java.util.Arrays.fill(s, (byte) 0);
        return h;
    }

    private static MessageDigest novoDigest() {
        try {
            return MessageDigest.getInstance(ALGORITMO);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 e exigido em toda JVM; se faltar, e erro de ambiente.
            throw new IllegalStateException("SHA-256 indisponivel nesta JVM");
        }
    }

    private record Decomposto(int iteracoes, byte[] salt, byte[] hash) {}

    /** Interpreta {@code sha256i$N$salt$hash}; devolve {@code null} se malformado. */
    private static Decomposto decompor(String armazenado) {
        String[] partes = armazenado.split("\\$", -1);
        if (partes.length != 4 || !PREFIXO.equals(partes[0])) {
            return null;
        }
        int n;
        try {
            if (partes[1].isEmpty() || partes[1].length() > 9 || !partes[1].chars().allMatch(c -> c >= '0' && c <= '9')) {
                return null;
            }
            n = Integer.parseInt(partes[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (n < 1 || n > ITERACOES_MAXIMAS) {
            return null;
        }
        byte[] salt = decodificarHex(partes[2], TAMANHO_SALT);
        byte[] hash = decodificarHex(partes[3], TAMANHO_HASH);
        if (salt == null || hash == null) {
            return null;
        }
        return new Decomposto(n, salt, hash);
    }

    private static byte[] decodificarHex(String hex, int tamanhoBytes) {
        if (hex.length() != tamanhoBytes * 2) {
            return null;
        }
        try {
            return HEX.parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
