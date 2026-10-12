package br.cefet.sd.cliente.desktop.rede;

/**
 * Politica de reconexao do Cliente_Desktop (parte pura: sem rede, sem relogio, sem threads).
 *
 * <p>Acompanha {@code atraso_reconexao} de {@code conexao.py} e {@code atrasoReconexao} de
 * {@code conexao.js}: atraso base {@code min(1000 * 2^n, 30000)} ms para a tentativa {@code n} (0, 1,
 * 2, ...), jitter de +-20% e no maximo 10 tentativas consecutivas.
 */
public final class PoliticaReconexao {

    /** Atraso base da primeira tentativa, em ms. */
    public static final long ATRASO_BASE_MS = 1000L;

    /** Atraso maximo (sem jitter) entre tentativas, em ms. */
    public static final long ATRASO_MAXIMO_MS = 30000L;

    /** Maximo de tentativas consecutivas de reconexao. */
    public static final int MAXIMO_TENTATIVAS = 10;

    /** Jitter, em pontos percentuais (+-20%). */
    public static final int JITTER_PERCENTUAL = 20;

    // Com base de 1 s e limite de 30 s, 2^5 ja satura; limitar o expoente evita estouro de long.
    private static final int EXPOENTE_MAXIMO = 16;

    private PoliticaReconexao() {}

    /**
     * Atraso base, sem jitter, antes da tentativa {@code n}, em ms: {@code min(1000 * 2^n, 30000)}.
     *
     * @throws IllegalArgumentException se {@code n} for negativo
     */
    public static long atrasoReconexao(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("A tentativa deve ser maior ou igual a zero");
        }
        return Math.min(ATRASO_BASE_MS << Math.min(n, EXPOENTE_MAXIMO), ATRASO_MAXIMO_MS);
    }

    /**
     * Atraso da tentativa {@code n} com jitter de +-20%, em ms; fica em {@code [0,8 * base, 1,2 *
     * base]}.
     *
     * @param aleatorio numero em {@code [0, 1]} (valores fora do intervalo sao limitados; NaN conta
     *     como 0). Injetavel para testes: com {@code aleatorio} fixo a funcao e pura.
     */
    public static double atrasoComJitter(int n, double aleatorio) {
        long base = atrasoReconexao(n);
        double r = Double.isNaN(aleatorio) ? 0.0 : Math.min(1.0, Math.max(0.0, aleatorio));
        // Percentuais nos extremos (80 e 120) evitam erro de ponto flutuante.
        double percentual = (100 - JITTER_PERCENTUAL) + 2.0 * JITTER_PERCENTUAL * r;
        return base * percentual / 100.0;
    }

    /**
     * Indica se cabe mais uma tentativa depois de {@code feitas} tentativas consecutivas ja
     * realizadas (limite de 10).
     */
    public static boolean podeTentarNovamente(int feitas) {
        return feitas >= 0 && feitas < MAXIMO_TENTATIVAS;
    }
}
