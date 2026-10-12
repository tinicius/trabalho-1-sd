package br.cefet.sd.cliente.desktop.sessao;

import br.cefet.sd.cliente.desktop.envio.EnviadorMensagens;
import br.cefet.sd.cliente.desktop.erros.ReportadorErros;
import br.cefet.sd.cliente.desktop.historico.ErroHistorico;
import br.cefet.sd.cliente.desktop.historico.HistoricoLocal;
import br.cefet.sd.cliente.desktop.rede.ConexaoTcp.ResultadoLogin;
import br.cefet.sd.cliente.desktop.rede.ErroRequisicao;
import br.cefet.sd.cliente.desktop.rede.EstadoConexao;
import br.cefet.sd.cliente.desktop.rede.EventoConexao;
import br.cefet.sd.cliente.desktop.rede.EventoConexao.EventoErro;
import br.cefet.sd.cliente.desktop.rede.EventoConexao.EventoEstado;
import br.cefet.sd.cliente.desktop.rede.EventoConexao.EventoHistorico;
import br.cefet.sd.cliente.desktop.rede.EventoConexao.EventoMensagem;
import br.cefet.sd.cliente.desktop.rede.EventoConexao.EventoReautenticado;
import br.cefet.sd.cliente.desktop.rede.MensagemRecebida;
import br.cefet.sd.cliente.desktop.sessao.ValidadorCadastro.ErroValidacao;
import br.cefet.sd.comum.protocolo.Perfil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Logica da sessao do Cliente_Desktop, sem Swing: login, cadastro, logout, recebimento de
 * mensagens e do historico do servidor, Historico_Local e reporte de erros.
 *
 * <ul>
 *   <li><b>A sala so existe depois do login confirmado</b>: {@link
 *       ObservadorSessao#aoAutenticar} e chamado uma unica vez por sessao, e apenas depois de {@code
 *       ok:true}. Em qualquer falha, a interface continua na tela de login.
 *   <li>Mensagens e historico que chegam entre a resposta do login e a abertura do Historico_Local
 *       ficam em fila e sao processados em ordem logo depois.
 *   <li>Sem duplicatas na exibicao e no Historico_Local: a exibicao usa o conjunto de {@code mid}
 *       ja mostrados; o {@link HistoricoLocal} e idempotente por {@code mid}. O
 *       remetente tambem so grava ao receber o eco do Middleware.
 *   <li>{@link EventoErro} e excecoes dos callbacks vao ao {@link ReportadorErros}; {@code
 *       aposLogin()} roda depois do login e do relogin automatico.
 * </ul>
 *
 * <p>Seguro para uso entre threads. O observador e chamado com o lock da sessao tomado, para
 * preservar a ordem; ele nao deve bloquear (a implementacao Swing usa {@code invokeLater}).
 */
public final class ControladorSessao implements AcoesSala {

    /** Abre o Historico_Local do usuario; injetavel para testes. */
    @FunctionalInterface
    public interface FabricaHistorico {
        HistoricoLocal abrir(String login) throws ErroHistorico;
    }

    private enum Fase {
        DESLOGADO,
        AUTENTICANDO,
        ATIVA
    }

    private final ServicoChat servico;
    private final FabricaHistorico fabricaHistorico;
    private final ReportadorErros reportador; // pode ser null
    private final ObservadorSessao observador;

    private final Object lock = new Object();
    private final List<EventoConexao> fila = new ArrayList<>();
    private final Set<String> exibidos = new HashSet<>();
    private Fase fase = Fase.DESLOGADO;
    private long geracao;
    private HistoricoLocal historico;

    /**
     * @param reportador reportador de erros (pode ser {@code null}, por exemplo em testes)
     */
    public ControladorSessao(
            ServicoChat servico,
            FabricaHistorico fabricaHistorico,
            ReportadorErros reportador,
            ObservadorSessao observador) {
        if (servico == null || fabricaHistorico == null || observador == null) {
            throw new IllegalArgumentException("servico, fabricaHistorico e observador sao obrigatorios");
        }
        this.servico = servico;
        this.fabricaHistorico = fabricaHistorico;
        this.reportador = reportador;
        this.observador = observador;
    }

    // ------------------------------------------------------------------
    // Consulta
    // ------------------------------------------------------------------

    /** {@code true} entre o login confirmado e o logout/encerramento. */
    public boolean sessaoAtiva() {
        synchronized (lock) {
            return fase == Fase.ATIVA;
        }
    }

    // ------------------------------------------------------------------
    // Login e cadastro
    // ------------------------------------------------------------------

    /**
     * Pede o login ao Middleware. Nao bloqueia. Login ou senha vazios nao geram requisicao. So uma
     * tentativa por vez: chamadas durante uma tentativa ou sessao ativa sao ignoradas.
     */
    public void entrar(String login, String senha) {
        Optional<ErroValidacao> invalido = ValidadorCadastro.validarLogin(login, senha);
        if (invalido.isPresent()) {
            observador.aoLoginRecusado(invalido.get().mensagem());
            return;
        }
        long minha;
        synchronized (lock) {
            if (fase != Fase.DESLOGADO) {
                return;
            }
            fase = Fase.AUTENTICANDO;
            fila.clear();
            minha = ++geracao;
            observador.aoOcupado(true);
        }
        CompletableFuture<ResultadoLogin> futuro;
        try {
            futuro = servico.login(login.strip(), senha);
        } catch (RuntimeException e) {
            futuro = CompletableFuture.failedFuture(e);
        }
        futuro.whenComplete(
                (resultado, erro) -> {
                    if (erro != null) {
                        aoLoginFalhar(minha, erro);
                    } else {
                        aoLoginConfirmado(minha, resultado.perfil());
                    }
                });
    }

    /** Pede o cadastro ao Middleware. Valida antes; so funciona na tela de login. */
    public void cadastrar(DadosCadastro dados) {
        Optional<ErroValidacao> invalido = ValidadorCadastro.validarCadastro(dados);
        if (invalido.isPresent()) {
            observador.aoCadastroRecusado(invalido.get().mensagem(), invalido.get().campo());
            return;
        }
        synchronized (lock) {
            if (fase != Fase.DESLOGADO) {
                return;
            }
            observador.aoOcupado(true);
        }
        String login = dados.login().strip();
        CompletableFuture<?> futuro;
        try {
            futuro = servico.registrar(
                    login, dados.apelido().strip(), dados.senha(), dados.avatar(), dados.cor().toUpperCase());
        } catch (RuntimeException e) {
            futuro = CompletableFuture.failedFuture(e);
        }
        futuro.whenComplete(
                (resultado, erro) -> {
                    synchronized (lock) {
                        observador.aoOcupado(false);
                        if (erro == null) {
                            observador.aoCadastroConcluido(login);
                        } else {
                            observador.aoCadastroRecusado(MensagensUsuario.paraErro(erro), campoDe(erro));
                        }
                    }
                });
    }

    private void aoLoginFalhar(long minha, Throwable erro) {
        synchronized (lock) {
            if (geracao != minha || fase != Fase.AUTENTICANDO) {
                return;
            }
            fase = Fase.DESLOGADO;
            fila.clear();
            observador.aoOcupado(false);
            observador.aoLoginRecusado(MensagensUsuario.paraErro(erro));
        }
        if (!(desembrulhar(erro) instanceof ErroRequisicao)) {
            reportar(erro, "sessao/login");
        }
    }

    private void aoLoginConfirmado(long minha, Perfil perfil) {
        synchronized (lock) {
            if (geracao != minha || fase != Fase.AUTENTICANDO) {
                return;
            }
        }
        HistoricoLocal aberto = null;
        boolean falhouHistorico = false;
        try {
            aberto = fabricaHistorico.abrir(perfil.login());
        } catch (ErroHistorico | RuntimeException e) {
            falhouHistorico = true;
            reportar(e, "historico/abrir");
        }
        List<MensagemRecebida> locais = aberto == null ? List.of() : aberto.listar();
        boolean fechar = false;
        synchronized (lock) {
            if (geracao != minha || fase != Fase.AUTENTICANDO) {
                fechar = true; // encerrado enquanto o historico abria
            } else {
                historico = aberto;
                exibidos.clear();
                for (MensagemRecebida m : locais) {
                    exibidos.add(m.mid());
                }
                fase = Fase.ATIVA;
                observador.aoOcupado(false);
                observador.aoAutenticar(perfil, locais);
                if (falhouHistorico) {
                    observador.aoAviso(
                            "Não foi possível abrir o histórico local. As mensagens desta sessão não serão salvas.");
                }
                List<EventoConexao> pendentes = new ArrayList<>(fila);
                fila.clear();
                for (EventoConexao e : pendentes) {
                    aoEventoDeConteudo(e);
                }
            }
        }
        if (fechar) {
            fecharHistorico(aberto);
            return;
        }
        posLogin();
    }

    // ------------------------------------------------------------------
    // Eventos da conexao
    // ------------------------------------------------------------------

    /** Ouvinte dos eventos da {@code ConexaoTcp}. Nunca lanca excecao. */
    public void aoEvento(EventoConexao evento) {
        try {
            switch (evento) {
                case EventoEstado e -> aoEstado(e);
                case EventoMensagem e -> aoEventoDeConteudoOuFila(e);
                case EventoHistorico e -> aoEventoDeConteudoOuFila(e);
                case EventoReautenticado e -> {
                    if (sessaoAtiva()) {
                        posLogin();
                    }
                }
                case EventoErro e -> reportar(e.erro(), "rede/" + e.contexto());
            }
        } catch (RuntimeException e) {
            reportar(e, "sessao/evento");
        }
    }

    private void aoEventoDeConteudoOuFila(EventoConexao evento) {
        synchronized (lock) {
            if (fase == Fase.AUTENTICANDO) {
                fila.add(evento);
            } else if (fase == Fase.ATIVA) {
                aoEventoDeConteudo(evento);
            }
            // DESLOGADO: nada a mostrar
        }
    }

    /** Chamar com {@code lock} tomado e fase ATIVA. */
    private void aoEventoDeConteudo(EventoConexao evento) {
        if (evento instanceof EventoMensagem e) {
            processar(List.of(e.mensagem()));
        } else if (evento instanceof EventoHistorico e) {
            processar(e.mensagens());
        }
    }

    /** Chamar com {@code lock} tomado. */
    private void processar(List<MensagemRecebida> mensagens) {
        List<MensagemRecebida> novas = new ArrayList<>();
        for (MensagemRecebida m : mensagens) {
            if (exibidos.add(m.mid())) {
                novas.add(m);
            }
        }
        if (!novas.isEmpty()) {
            observador.aoNovasMensagens(List.copyOf(novas));
        }
        if (historico != null) {
            try {
                historico.incorporarRecebidas(mensagens);
            } catch (ErroHistorico e) {
                reportar(e, "historico/incorporar");
            }
        }
    }

    private void aoEstado(EventoEstado evento) {
        String encerrar = null;
        synchronized (lock) {
            if (fase != Fase.ATIVA) {
                return;
            }
            observador.aoEstadoConexao(evento.estado(), evento.detalhe());
            if (evento.estado() == EstadoConexao.DESCONECTADO
                    && "credenciais_invalidas".equals(evento.detalhe().get("motivo"))) {
                encerrar = "Seu login ou senha não são mais válidos. Entre novamente.";
            }
        }
        if (encerrar != null) {
            encerrarLocal(encerrar);
        }
    }

    // ------------------------------------------------------------------
    // Acoes da sala
    // ------------------------------------------------------------------

    @Override
    public EnviadorMensagens enviador() {
        return (tipo, conteudo) -> {
            if (!sessaoAtiva()) {
                return CompletableFuture.failedFuture(
                        new ErroRequisicao(ErroRequisicao.SEM_CONEXAO, "Sessao nao autenticada"));
            }
            return servico.enviar(tipo, conteudo);
        };
    }

    @Override
    public void tentarNovamente() {
        if (!sessaoAtiva()) {
            return;
        }
        if (!servico.tentarNovamente()) {
            encerrarLocal("Não foi possível retomar a conexão. Entre novamente.");
        }
    }

    @Override
    public void sair() {
        if (encerrarLocal(null)) {
            try {
                servico.logout().whenComplete((r, erro) -> {
                    if (erro != null && !(desembrulhar(erro) instanceof ErroRequisicao)) {
                        reportar(erro, "sessao/logout");
                    }
                });
            } catch (RuntimeException e) {
                reportar(e, "sessao/logout");
            }
        }
    }

    /** Fechamento do aplicativo: fecha o historico e a conexao, sem aviso a interface. */
    public void encerrar() {
        HistoricoLocal fechar;
        synchronized (lock) {
            geracao++;
            fase = Fase.DESLOGADO;
            fila.clear();
            exibidos.clear();
            fechar = historico;
            historico = null;
        }
        fecharHistorico(fechar);
        try {
            servico.fechar();
        } catch (RuntimeException e) {
            reportar(e, "sessao/fechar");
        }
    }

    /**
     * Volta ao estado "deslogado": fecha o historico e avisa a interface. Devolve {@code false} se
     * ja estava deslogado.
     */
    private boolean encerrarLocal(String aviso) {
        HistoricoLocal fechar;
        synchronized (lock) {
            if (fase == Fase.DESLOGADO) {
                return false;
            }
            geracao++;
            fase = Fase.DESLOGADO;
            fila.clear();
            exibidos.clear();
            fechar = historico;
            historico = null;
            observador.aoOcupado(false);
            observador.aoSessaoEncerrada(aviso);
        }
        fecharHistorico(fechar);
        return true;
    }

    // ------------------------------------------------------------------
    // Utilitarios
    // ------------------------------------------------------------------

    private void posLogin() {
        if (reportador != null) {
            reportador.aposLogin();
        }
    }

    private void reportar(Throwable erro, String modulo) {
        if (reportador == null || erro == null) {
            return;
        }
        reportador.reportar(erro, modulo);
    }

    private void fecharHistorico(HistoricoLocal h) {
        if (h != null) {
            h.close();
        }
    }

    private static Throwable desembrulhar(Throwable erro) {
        Throwable atual = erro;
        while ((atual instanceof java.util.concurrent.CompletionException
                        || atual instanceof java.util.concurrent.ExecutionException)
                && atual.getCause() != null) {
            atual = atual.getCause();
        }
        return atual;
    }

    private static String campoDe(Throwable erro) {
        return desembrulhar(erro) instanceof ErroRequisicao e ? e.campo() : null;
    }
}
