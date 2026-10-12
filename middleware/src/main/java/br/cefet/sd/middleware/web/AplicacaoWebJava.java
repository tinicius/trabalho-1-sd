package br.cefet.sd.middleware.web;

import br.cefet.sd.middleware.config.Configuracao;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AplicacaoWebJava: servidor HTTP estatico do Cliente_Web.
 *
 * <p>Abre um {@link HttpServer} com dois contextos somente de leitura: {@code /} serve a pasta
 * {@code cliente-web/} e {@code /recursos/} serve a pasta {@code recursos/}. A pasta
 * {@code dados/} nunca e raiz de contexto, e a classe nem recebe esse caminho.
 *
 * <p>Regras: apenas GET e HEAD (demais metodos recebem 405); o caminho e decodificado uma vez,
 * normalizado e qualquer resolucao fora da raiz (segmento {@code ..}, barra invertida, byte nulo,
 * link simbolico que escapa) recebe 403; {@code Content-Type} por extensao; {@code Cache-Control:
 * no-cache}; {@code Content-Security-Policy} com {@code default-src 'self'} e {@code connect-src
 * 'self' ws: wss:}. Esta classe nao conhece chaves nem acessa banco, repositorios ou logs.
 */
public final class AplicacaoWebJava implements AutoCloseable {

    /** Valor do cabecalho {@code Content-Security-Policy}. */
    public static final String POLITICA_CSP = "default-src 'self'; connect-src 'self' ws: wss:";

    private static final String INDICE = "index.html";

    private static final Map<String, String> TIPOS =
            Map.ofEntries(
                    Map.entry("html", "text/html; charset=utf-8"),
                    Map.entry("htm", "text/html; charset=utf-8"),
                    Map.entry("css", "text/css; charset=utf-8"),
                    Map.entry("js", "text/javascript; charset=utf-8"),
                    Map.entry("mjs", "text/javascript; charset=utf-8"),
                    Map.entry("json", "application/json; charset=utf-8"),
                    Map.entry("txt", "text/plain; charset=utf-8"),
                    Map.entry("png", "image/png"),
                    Map.entry("jpg", "image/jpeg"),
                    Map.entry("jpeg", "image/jpeg"),
                    Map.entry("gif", "image/gif"),
                    Map.entry("webp", "image/webp"),
                    Map.entry("svg", "image/svg+xml"),
                    Map.entry("ico", "image/x-icon"),
                    Map.entry("woff", "font/woff"),
                    Map.entry("woff2", "font/woff2"));

    private final int porta;
    private final Path webRaiz;
    private final Path recursosRaiz;
    private HttpServer servidor;
    private ExecutorService executor;

    /**
     * @param porta porta HTTP (0 escolhe uma porta livre; ver {@link #portaEfetiva()})
     * @param webRaiz pasta servida em {@code /}
     * @param recursosRaiz pasta servida em {@code /recursos/}
     */
    public AplicacaoWebJava(int porta, Path webRaiz, Path recursosRaiz) {
        this.porta = porta;
        this.webRaiz = webRaiz;
        this.recursosRaiz = recursosRaiz;
    }

    /** Cria a aplicacao com porta HTTP, {@code web.raiz} e {@code recursos.raiz} da configuracao. */
    public static AplicacaoWebJava deConfiguracao(Configuracao c) {
        return new AplicacaoWebJava(c.portaHttp(), c.webRaiz(), c.recursosRaiz());
    }

    /** Abre a porta e comeca a atender. Chamar duas vezes nao tem efeito. */
    public synchronized void iniciar() throws IOException {
        if (servidor != null) {
            return;
        }
        HttpServer s = HttpServer.create(new InetSocketAddress(porta), 0);
        s.createContext("/", new ManipuladorEstatico(webRaiz, "/"));
        s.createContext("/recursos/", new ManipuladorEstatico(recursosRaiz, "/recursos/"));
        ExecutorService e = Executors.newVirtualThreadPerTaskExecutor();
        s.setExecutor(e);
        s.start();
        this.servidor = s;
        this.executor = e;
    }

    /** Porta realmente aberta; util quando construido com porta 0. */
    public synchronized int portaEfetiva() {
        if (servidor == null) {
            throw new IllegalStateException("servidor HTTP nao iniciado");
        }
        return servidor.getAddress().getPort();
    }

    /** Para o servidor. Pode ser chamado mais de uma vez. */
    public synchronized void parar() {
        if (servidor == null) {
            return;
        }
        servidor.stop(0);
        executor.shutdownNow();
        servidor = null;
        executor = null;
    }

    @Override
    public void close() {
        parar();
    }

    /** Tipo MIME pela extensao do nome do arquivo; desconhecida vira {@code octet-stream}. */
    static String tipoPorExtensao(String nome) {
        int ponto = nome.lastIndexOf('.');
        if (ponto < 0 || ponto == nome.length() - 1) {
            return "application/octet-stream";
        }
        String ext = nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
        return TIPOS.getOrDefault(ext, "application/octet-stream");
    }

    /**
     * Decodifica o caminho bruto da URL (percent-encoding, uma unica vez, sem tratar {@code +}).
     *
     * @return os segmentos nao vazios, ou {@code null} se o caminho for malformado ou suspeito
     */
    static List<String> segmentosSeguros(String caminhoBruto) {
        byte[] bytes = new byte[caminhoBruto.length()];
        int n = 0;
        for (int i = 0; i < caminhoBruto.length(); i++) {
            char c = caminhoBruto.charAt(i);
            if (c == '%') {
                if (i + 2 >= caminhoBruto.length()) {
                    return null;
                }
                int h = Character.digit(caminhoBruto.charAt(i + 1), 16);
                int l = Character.digit(caminhoBruto.charAt(i + 2), 16);
                if (h < 0 || l < 0) {
                    return null;
                }
                bytes[n++] = (byte) (h * 16 + l);
                i += 2;
            } else if (c < 0x80) {
                bytes[n++] = (byte) c;
            } else {
                return null; // caracteres nao ASCII devem chegar percent-encoded
            }
        }
        String decodificado;
        try {
            decodificado =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes, 0, n))
                            .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        if (decodificado.indexOf('\0') >= 0 || decodificado.indexOf('\\') >= 0) {
            return null;
        }
        List<String> segmentos = new ArrayList<>();
        for (String seg : decodificado.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..") || seg.startsWith(".") || seg.indexOf(':') >= 0) {
                return null;
            }
            segmentos.add(seg);
        }
        return segmentos;
    }

    private static final class ManipuladorEstatico implements HttpHandler {
        private final Path raiz;
        private final String prefixo;

        ManipuladorEstatico(Path raiz, String prefixo) {
            this.raiz = raiz.toAbsolutePath().normalize();
            this.prefixo = prefixo;
        }

        @Override
        public void handle(HttpExchange troca) throws IOException {
            try (troca) {
                atender(troca);
            } catch (RuntimeException e) {
                // Sem detalhes na resposta; a falha nao deve derrubar o servidor.
                enviarErro(troca, 500, "Erro interno");
            }
        }

        private void atender(HttpExchange troca) throws IOException {
            String metodo = troca.getRequestMethod();
            boolean head = metodo.equals("HEAD");
            if (!head && !metodo.equals("GET")) {
                troca.getResponseHeaders().set("Allow", "GET, HEAD");
                enviarErro(troca, 405, "Metodo nao permitido");
                return;
            }
            String bruto = troca.getRequestURI().getRawPath();
            if (bruto == null || !bruto.startsWith("/")) {
                enviarErro(troca, 404, "Nao encontrado");
                return;
            }
            // Em /recursos/ o prefixo sai do caminho; segmentos sao validados apos a decodificacao.
            List<String> segmentos = segmentosSeguros(bruto);
            if (segmentos == null) {
                enviarErro(troca, 403, "Acesso negado");
                return;
            }
            if (!prefixo.equals("/")) {
                // segmentos[0] e "recursos" (o proprio nome do contexto)
                if (segmentos.isEmpty() || !segmentos.get(0).equals("recursos")) {
                    enviarErro(troca, 404, "Nao encontrado");
                    return;
                }
                segmentos = segmentos.subList(1, segmentos.size());
            }
            Path alvo = raiz;
            for (String s : segmentos) {
                alvo = alvo.resolve(s);
            }
            alvo = alvo.normalize();
            if (!alvo.startsWith(raiz)) {
                enviarErro(troca, 403, "Acesso negado");
                return;
            }
            if (Files.isDirectory(alvo)) {
                alvo = alvo.resolve(INDICE);
            }
            if (!Files.isRegularFile(alvo)) {
                enviarErro(troca, 404, "Nao encontrado");
                return;
            }
            Path real = alvo.toRealPath();
            if (!real.startsWith(raiz.toRealPath())) {
                enviarErro(troca, 403, "Acesso negado"); // link simbolico escapando da raiz
                return;
            }
            long tamanho = Files.size(real);
            Headers h = troca.getResponseHeaders();
            cabecalhosComuns(h);
            h.set("Content-Type", tipoPorExtensao(real.getFileName().toString()));
            if (head) {
                h.set("Content-Length", Long.toString(tamanho));
                troca.sendResponseHeaders(200, -1);
                return;
            }
            troca.sendResponseHeaders(200, tamanho == 0 ? -1 : tamanho);
            if (tamanho > 0) {
                try (InputStream in = Files.newInputStream(real);
                        OutputStream out = troca.getResponseBody()) {
                    in.transferTo(out);
                }
            }
        }

        private static void cabecalhosComuns(Headers h) {
            h.set("Cache-Control", "no-cache");
            h.set("Content-Security-Policy", POLITICA_CSP);
            h.set("X-Content-Type-Options", "nosniff");
        }

        private static void enviarErro(HttpExchange troca, int status, String texto)
                throws IOException {
            Headers h = troca.getResponseHeaders();
            cabecalhosComuns(h);
            h.set("Content-Type", "text/plain; charset=utf-8");
            byte[] corpo = texto.getBytes(StandardCharsets.UTF_8);
            if (troca.getRequestMethod().equals("HEAD")) {
                troca.sendResponseHeaders(status, -1);
                return;
            }
            troca.sendResponseHeaders(status, corpo.length);
            try (OutputStream out = troca.getResponseBody()) {
                out.write(corpo);
            }
        }
    }
}
