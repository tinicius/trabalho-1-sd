package br.cefet.sd.cliente.desktop.erros;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;

/** Conversoes JSON compactas dos eventos do Buffer_Offline_Erros. */
final class EventosJson {

    private static final ObjectMapper MAPEADOR = new ObjectMapper();

    private EventosJson() {}

    /** Forma gravada no arquivo (mesma ordem de campos de {@code erros.py}). */
    static ObjectNode paraArquivo(EventoPendente e) {
        ObjectNode no = JsonNodeFactory.instance.objectNode();
        no.put("evid", e.evid());
        no.put("ts", e.ts());
        no.put("modulo", e.modulo());
        no.put("tipo", e.tipo());
        no.put("descricao", e.descricao());
        no.put("pilha", e.pilha());
        no.put("dispositivo", e.dispositivo());
        no.put("cliente", e.cliente());
        no.put("tentativas", e.tentativas());
        return no;
    }

    /** Forma enviada no {@code error_report}: sem {@code tentativas}, com {@code descartados}. */
    static ObjectNode paraEnvio(EventoPendente e, int descartados) {
        ObjectNode no = JsonNodeFactory.instance.objectNode();
        no.put("ts", e.ts());
        no.put("modulo", e.modulo());
        no.put("tipo", e.tipo());
        no.put("descricao", e.descricao());
        no.put("pilha", e.pilha());
        no.put("dispositivo", e.dispositivo());
        no.put("cliente", e.cliente());
        no.put("evid", e.evid());
        no.put("descartados", descartados);
        return no;
    }

    /** JSON compacto em uma linha. */
    static String texto(JsonNode no) {
        try {
            return MAPEADOR.writeValueAsString(no);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar evento", e);
        }
    }

    /** Tamanho, em bytes, do JSON compacto UTF-8 de {@code no}. */
    static int tamanhoEmBytes(JsonNode no) {
        return texto(no).getBytes(StandardCharsets.UTF_8).length;
    }

    /** Interpreta uma linha do arquivo; {@code null} se nao for JSON. */
    static JsonNode ler(String linha) {
        try {
            return MAPEADOR.readTree(linha);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
