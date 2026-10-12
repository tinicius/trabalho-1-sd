package br.cefet.sd.comum.emoticons;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tabela_de_Conversao: pares (emoticon, emoji) lidos de {@code recursos/emoticons.json}, a fonte
 * unica compartilhada pelos tres Clientes.
 *
 * <p>O formato do arquivo e uma lista JSON de objetos {@code {"emoticon":":)","emoji":"🙂"}}. A
 * carga aceita um caminho ou o conteudo ja lido (injetavel para testes). A tabela e imutavel e
 * guarda os pares em ordem decrescente de comprimento do emoticon (o mais longo primeiro), como
 * exige o algoritmo de {@link ConversorEmoticons}. Em caso de emoticon repetido, vale o primeiro.
 */
public final class TabelaConversao {

    /** Um par da tabela. */
    public record Par(String emoticon, String emoji) {}

    private static final ObjectMapper MAPEADOR = new ObjectMapper();

    private final List<Par> pares;

    private TabelaConversao(List<Par> pares) {
        this.pares = pares;
    }

    /** Monta a tabela a partir de pares; ignora repetidos e ordena do mais longo ao mais curto. */
    public static TabelaConversao de(List<Par> entrada) {
        if (entrada == null) {
            throw new IllegalArgumentException("Pares ausentes");
        }
        Set<String> vistos = new HashSet<>();
        List<Par> lista = new ArrayList<>();
        for (Par p : entrada) {
            if (p == null || p.emoticon() == null || p.emoji() == null || p.emoticon().isEmpty()) {
                throw new IllegalArgumentException("Par invalido na Tabela_de_Conversao");
            }
            if (vistos.add(p.emoticon())) {
                lista.add(p);
            }
        }
        // Ordenacao estavel: emoticons de mesmo tamanho mantem a ordem do arquivo.
        lista.sort(Comparator.comparingInt((Par p) -> p.emoticon().length()).reversed());
        return new TabelaConversao(Collections.unmodifiableList(lista));
    }

    /** Le a tabela do conteudo JSON (lista de objetos com "emoticon" e "emoji"). */
    public static TabelaConversao deJson(String conteudo) {
        if (conteudo == null) {
            throw new IllegalArgumentException("Conteudo ausente");
        }
        JsonNode raiz;
        try {
            raiz = MAPEADOR.readTree(conteudo);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON invalido na Tabela_de_Conversao", e);
        }
        if (raiz == null || !raiz.isArray()) {
            throw new IllegalArgumentException("A Tabela_de_Conversao deve ser uma lista JSON");
        }
        List<Par> lista = new ArrayList<>();
        for (JsonNode no : raiz) {
            JsonNode emoticon = no.get("emoticon");
            JsonNode emoji = no.get("emoji");
            if (emoticon == null || !emoticon.isTextual() || emoji == null || !emoji.isTextual()) {
                throw new IllegalArgumentException("Par invalido na Tabela_de_Conversao");
            }
            lista.add(new Par(emoticon.asText(), emoji.asText()));
        }
        return de(lista);
    }

    /** Le a tabela de um arquivo JSON em UTF-8. */
    public static TabelaConversao carregar(Path caminho) throws IOException {
        return deJson(Files.readString(caminho, StandardCharsets.UTF_8));
    }

    /** Pares em ordem decrescente de comprimento do emoticon (lista imutavel). */
    public List<Par> pares() {
        return pares;
    }
}
