package br.cefet.sd.cliente.desktop.sessao;

import br.cefet.sd.cliente.desktop.rede.MensagemRecebida;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;

/** Funcoes puras de exibicao de mensagens (sem Swing): data/hora local, marcadores e ordem. */
public final class FormatadorMensagem {

    /** Texto exibido quando a mensagem nao pode ser decifrada. */
    public static final String TEXTO_ILEGIVEL = "[mensagem ilegível]";

    /** Cor usada quando a cor da conta nao esta no formato {@code #RRGGBB}. */
    public static final int COR_PADRAO = 0x000000;

    private static final DateTimeFormatter FORMATO_DATA_HORA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /** Ordem de exibicao: {@code (ts, mid)}, a mesma do Historico_Local. */
    public static final Comparator<MensagemRecebida> ORDEM =
            Comparator.comparing(MensagemRecebida::ts).thenComparing(MensagemRecebida::mid);

    private FormatadorMensagem() {}

    /**
     * Converte o {@code ts} do servidor (ISO-8601 UTC) para a data/hora local da {@code zona}. Se o
     * texto nao for um instante valido, devolve o proprio texto.
     */
    public static String dataHora(String tsIso, ZoneId zona) {
        try {
            return FORMATO_DATA_HORA.format(Instant.parse(tsIso).atZone(zona));
        } catch (DateTimeParseException | NullPointerException e) {
            return tsIso == null ? "" : tsIso;
        }
    }

    /** Marcador textual de uma Figurinha sem imagem, por exemplo {@code [figurinha fig_99]}. */
    public static String marcadorFigurinha(String id) {
        return "[figurinha " + id + "]";
    }

    /** Marcador textual de um avatar sem imagem, por exemplo {@code [av_99]}. */
    public static String marcadorAvatar(String id) {
        return "[" + id + "]";
    }

    /** Cor {@code #RRGGBB} como inteiro RGB; {@link #COR_PADRAO} se o formato for invalido. */
    public static int rgbDe(String cor) {
        if (cor != null && cor.length() == 7 && cor.charAt(0) == '#') {
            try {
                return Integer.parseInt(cor.substring(1), 16);
            } catch (NumberFormatException e) {
                // cai no padrao
            }
        }
        return COR_PADRAO;
    }

    /** Cor {@code #RRGGBB} (maiusculas) a partir de um inteiro RGB. */
    public static String hexDe(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /**
     * Indice em que {@code nova} deve entrar em {@code ordenada} (ordenada por {@link #ORDEM}) para
     * manter a ordem; depois de elementos iguais.
     */
    public static int posicaoDeInsercao(List<MensagemRecebida> ordenada, MensagemRecebida nova) {
        int baixo = 0;
        int alto = ordenada.size();
        while (baixo < alto) {
            int meio = (baixo + alto) >>> 1;
            if (ORDEM.compare(ordenada.get(meio), nova) <= 0) {
                baixo = meio + 1;
            } else {
                alto = meio;
            }
        }
        return baixo;
    }
}
