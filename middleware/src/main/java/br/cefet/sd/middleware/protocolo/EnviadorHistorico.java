package br.cefet.sd.middleware.protocolo;

import br.cefet.sd.comum.cifra.Chave;
import br.cefet.sd.comum.cifra.CifraManual;
import br.cefet.sd.comum.cifra.DecifragemFalhouException;
import br.cefet.sd.comum.protocolo.CodigoErro;
import br.cefet.sd.comum.protocolo.MensagemChat;
import br.cefet.sd.comum.protocolo.Perfil;
import br.cefet.sd.comum.protocolo.ProtocoloJson;
import br.cefet.sd.middleware.contas.Conta;
import br.cefet.sd.middleware.contas.ContasRepositorio;
import br.cefet.sd.middleware.contas.ServicoIndisponivelException;
import br.cefet.sd.middleware.repositorio.RegistroConversa;
import br.cefet.sd.middleware.repositorio.RepositorioConversas;
import br.cefet.sd.middleware.sessao.Sessao;
import br.cefet.sd.middleware.tempo.Relogio;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Envia a uma Sessao as Mensagens da Janela_7_Dias do Repositorio_Conversas, em quadros
 * {@code history} de ate {@value #TAMANHO_LOTE} mensagens.
 *
 * <p>Componente compartilhado: o {@code login} o usa para o historico automatico ({@code id}
 * nulo) e o {@code history} o usara para responder a requisicao ({@code id} da requisicao). Cada
 * registro e decifrado com a Chave_Repositorio, cifrado de novo com a Chave_Transito (nonce novo) e
 * acompanhado do perfil publico do remetente, lido de {@link ContasRepositorio}; o texto legivel
 * existe somente em variaveis locais. Registros ilegiveis (nao decifram) sao ignorados e relatados
 * uma unica vez no Log_Erros, sem conteudo.
 *
 * <p>Sem mensagens na janela, envia um unico quadro vazio com {@code lote:1} e {@code ultimo:true},
 * para que o Cliente saiba que o historico terminou. Se a leitura falhar, registra o erro no
 * Log_Erros e responde um quadro {@code history} com {@code ok:false} e {@code ERRO_INTERNO}
 * ({@code SERVICO_INDISPONIVEL} se o Banco_de_Dados falhar ao buscar perfis); nesse caso nenhum lote
 * parcial e enviado, porque os lotes so saem depois que todos estao montados.
 *
 * <p>Instancias sao seguras entre threads.
 */
public final class EnviadorHistorico {

    /** Maximo de mensagens por quadro {@code history}. */
    public static final int TAMANHO_LOTE = 100;

    private static final String MODULO = "EnviadorHistorico";

    private final ProcessadorProtocolo processador;
    private final RepositorioConversas repositorio;
    private final ContasRepositorio contas;
    private final Chave chaveRepositorio;
    private final Chave chaveTransito;
    private final Relogio relogio;

    /**
     * @param processador usado para registrar erros no Log_Erros
     * @param repositorio Repositorio_Conversas (conteudo cifrado com a Chave_Repositorio)
     * @param contas fonte dos perfis dos remetentes
     * @param chaveRepositorio Chave_Repositorio (decifra o que esta gravado)
     * @param chaveTransito Chave_Transito (cifra o que vai ao Cliente)
     * @param relogio define o inicio da Janela_7_Dias
     */
    public EnviadorHistorico(
            ProcessadorProtocolo processador,
            RepositorioConversas repositorio,
            ContasRepositorio contas,
            Chave chaveRepositorio,
            Chave chaveTransito,
            Relogio relogio) {
        this.processador = Objects.requireNonNull(processador, "processador");
        this.repositorio = Objects.requireNonNull(repositorio, "repositorio");
        this.contas = Objects.requireNonNull(contas, "contas");
        this.chaveRepositorio = Objects.requireNonNull(chaveRepositorio, "chaveRepositorio");
        this.chaveTransito = Objects.requireNonNull(chaveTransito, "chaveTransito");
        this.relogio = Objects.requireNonNull(relogio, "relogio");
    }

    /** Historico completo da Janela_7_Dias. */
    public void enviar(Sessao sessao, String id) {
        enviar(sessao, id, null);
    }

    /**
     * Envia as Mensagens com {@code ts >= desde}, em ordem de gravacao.
     *
     * @param sessao destino
     * @param id {@code id} dos quadros ({@code null} no envio automatico apos o login)
     * @param desde inicio pedido, ou {@code null}; e limitado ao inicio da Janela_7_Dias (um valor
     *     anterior e substituido por {@code agora - 7 dias})
     */
    public void enviar(Sessao sessao, String id, Instant desde) {
        Objects.requireNonNull(sessao, "sessao");
        List<String> quadros;
        try {
            quadros = montar(sessao, id, desde);
        } catch (ServicoIndisponivelException e) {
            processador.registrarErro(MODULO, e, sessao);
            sessao.enviar(ProtocoloJson.serializar(ProtocoloJson.respostaErro(
                    ProtocoloJson.OP_HISTORY, id, CodigoErro.SERVICO_INDISPONIVEL, null)));
            return;
        } catch (Exception e) {
            processador.registrarErro(MODULO, e, sessao);
            sessao.enviar(ProtocoloJson.serializar(ProtocoloJson.respostaErro(
                    ProtocoloJson.OP_HISTORY, id, CodigoErro.ERRO_INTERNO, null)));
            return;
        }
        for (String quadro : quadros) {
            sessao.enviar(quadro);
        }
    }

    private List<String> montar(Sessao sessao, String id, Instant desde) throws Exception {
        Instant inicioJanela = relogio.inicioJanelaConversas();
        Instant inicio = desde == null || desde.isBefore(inicioJanela) ? inicioJanela : desde;
        List<RegistroConversa> registros = repositorio.janela(inicio);

        Map<String, Perfil> perfis = new HashMap<>();
        List<MensagemChat> mensagens = new ArrayList<>(registros.size());
        int ilegiveis = 0;
        for (RegistroConversa registro : registros) {
            String conteudoTransito;
            try {
                String texto = CifraManual.decifrar(chaveRepositorio, registro.conteudo());
                conteudoTransito = CifraManual.cifrar(chaveTransito, texto);
            } catch (DecifragemFalhouException e) {
                ilegiveis++;
                continue;
            }
            Perfil remetente = perfis.computeIfAbsent(registro.remetente(), this::perfilDe);
            mensagens.add(new MensagemChat(
                    registro.mid(), remetente, registro.tsTexto(), registro.tipo(), conteudoTransito));
        }
        if (ilegiveis > 0) {
            // Texto fixo e sem causa: nada do conteudo cifrado ou decifrado vai ao log.
            processador.registrarErro(
                    MODULO,
                    new DecifragemFalhouException(
                            "Registros do Repositorio_Conversas ignorados por falha de decifragem: "
                                    + ilegiveis),
                    sessao);
        }

        List<String> quadros = new ArrayList<>();
        int total = mensagens.size();
        int lotes = Math.max(1, (total + TAMANHO_LOTE - 1) / TAMANHO_LOTE);
        for (int lote = 1; lote <= lotes; lote++) {
            int de = (lote - 1) * TAMANHO_LOTE;
            int ate = Math.min(total, de + TAMANHO_LOTE);
            List<MensagemChat> fatia = mensagens.subList(de, ate);
            quadros.add(ProtocoloJson.serializar(
                    ProtocoloJson.eventoHistory(fatia, lote, lote == lotes, id)));
        }
        return quadros;
    }

    /**
     * Perfil publico do remetente. Se a Conta nao existe mais, usa um perfil neutro com o proprio
     * login, para que a Mensagem continue exibivel.
     */
    private Perfil perfilDe(String login) {
        Optional<Conta> conta = contas.buscar(login);
        return conta.map(Conta::perfil)
                .orElseGet(() -> new Perfil(login, login, "av_01", "#000000"));
    }
}
