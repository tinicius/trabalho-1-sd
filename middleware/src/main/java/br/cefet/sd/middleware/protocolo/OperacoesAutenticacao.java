package br.cefet.sd.middleware.protocolo;

import br.cefet.sd.comum.cifra.Chave;
import br.cefet.sd.comum.cifra.CifraManual;
import br.cefet.sd.comum.cifra.DecifragemFalhouException;
import br.cefet.sd.comum.protocolo.CodigoErro;
import br.cefet.sd.comum.protocolo.Perfil;
import br.cefet.sd.comum.protocolo.ProtocoloJson;
import br.cefet.sd.middleware.contas.Conta;
import br.cefet.sd.middleware.contas.ContasRepositorio;
import br.cefet.sd.middleware.contas.LoginDuplicadoException;
import br.cefet.sd.middleware.contas.NovaConta;
import br.cefet.sd.middleware.contas.ServicoIndisponivelException;
import br.cefet.sd.middleware.contas.TratamentoSenhas;
import br.cefet.sd.middleware.logs.EventoAcesso;
import br.cefet.sd.middleware.logs.LogAcessos;
import br.cefet.sd.middleware.sessao.Distribuidor;
import br.cefet.sd.middleware.sessao.Sessao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Operacoes {@code register} e {@code login} do Protocolo. Nenhuma delas exige Sessao
 * autenticada.
 *
 * <p>Uso: {@code new OperacoesAutenticacao(...).registrarEm(processador)}.
 *
 * <h2>register</h2>
 *
 * Decifra {@code dados} (Chave_Transito) em memoria e valida, nesta ordem, {@code login},
 * {@code apelido}, {@code senha}, {@code avatar} e {@code cor}. Campo omitido, nulo ou vazio gera
 * {@code CAMPO_AUSENTE} com {@code erro.campo} do primeiro faltante (apelido so com espacos conta como
 * vazio); valor fora do formato gera {@code CAMPO_INVALIDO}. A cor e guardada em maiusculas e o
 * apelido sem espacos nas pontas. A senha vira o valor do Tratamento_de_Senhas antes de chegar ao
 * repositorio. A unicidade e decidida pelo repositorio ({@code LOGIN_DUPLICADO}); banco inacessivel
 * gera {@code SERVICO_INDISPONIVEL} e um registro no Log_Erros.
 *
 * <h2>login</h2>
 *
 * <ol>
 *   <li>Conexao ja autenticada: {@code JA_AUTENTICADO} (nada e decifrado nem gravado).
 *   <li>Decifra {@code dados} ({@code DECIFRAGEM_FALHOU} sem gravar conteudo; {@code FORMATO_INVALIDO}
 *       se nao for um objeto JSON com {@code login} e {@code senha} em texto).
 *   <li>Credenciais invalidas ({@code CREDENCIAIS_INVALIDAS}, identica para login inexistente e senha
 *       incorreta, com trabalho de hash equivalente): grava {@code login_recusado}, conta a falha na
 *       conexao e, na quinta, encerra a conexao depois de entregar a resposta ({@code falhas_de_login}).
 *   <li>Credenciais validas: {@link Sessao#autenticar} (id de 16 bytes por {@code SecureRandom}),
 *       {@code login_ok}, resposta com {@code sessao} e {@code perfil}, registro no {@link Distribuidor}
 *       <b>antes</b> da leitura do historico e envio automatico do {@code history} pelo
 *       {@link EnviadorHistorico}.
 * </ol>
 *
 * Falhas de decifragem, de formato e de banco nao contam como falha de autenticacao.
 *
 * <h2>Log_Acessos</h2>
 *
 * O evento {@code conexao} e gravado na primeira {@code register} ou {@code login} da conexao
 * ({@link #registrarConexao(Requisicao)}, idempotente por conexao) e, nesse mesmo momento, a Sessao
 * passa a ter um observador que grava {@code desconexao} com o motivo quando ela se encerra. Quem
 * encerra a Sessao ({@code logout}, listeners, fila cheia) nao deve gravar {@code desconexao} por
 * conta propria para essas conexoes. Falha ao gravar no Log_Acessos nao interrompe o fluxo: vai para
 * {@code stderr} (so a classe da excecao). Em {@code login_recusado} o login so e gravado se casar com
 * {@code ^[a-z0-9_]{3,20}$}. Senhas nunca vao ao log.
 */
public final class OperacoesAutenticacao {

    /** Padrao do login. */
    static final Pattern PADRAO_LOGIN = Pattern.compile("[a-z0-9_]{3,20}");

    private static final Pattern PADRAO_AVATAR = Pattern.compile("av_(0[1-9]|1[0-2])");
    private static final Pattern PADRAO_COR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern PADRAO_DISPOSITIVO = Pattern.compile("[0-9A-Za-z-]{1,64}");

    private static final int APELIDO_MAXIMO = 30;
    private static final int SENHA_MINIMA = 6;
    private static final int SENHA_MAXIMA = 72;

    /** Falhas de autenticacao que encerram a conexao (a quinta). */
    static final int FALHAS_PARA_ENCERRAR = 5;

    static final String MOTIVO_FALHAS_DE_LOGIN = "falhas_de_login";

    private static final String ATRIBUTO_CONEXAO = "acessos.conexao_registrada";

    private static final ObjectMapper MAPEADOR =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ContasRepositorio contas;
    private final TratamentoSenhas tratamentoSenhas;
    private final Chave chaveTransito;
    private final Distribuidor distribuidor;
    private final LogAcessos logAcessos;
    private final EnviadorHistorico enviadorHistorico;

    /**
     * @param contas repositorio de Contas
     * @param tratamentoSenhas gera e verifica os valores de senha
     * @param chaveTransito Chave_Transito, que decifra {@code dados}
     * @param distribuidor recebe a Sessao autenticada
     * @param logAcessos destino dos eventos de acesso
     * @param enviadorHistorico envia o historico automatico apos o login
     */
    public OperacoesAutenticacao(
            ContasRepositorio contas,
            TratamentoSenhas tratamentoSenhas,
            Chave chaveTransito,
            Distribuidor distribuidor,
            LogAcessos logAcessos,
            EnviadorHistorico enviadorHistorico) {
        this.contas = Objects.requireNonNull(contas, "contas");
        this.tratamentoSenhas = Objects.requireNonNull(tratamentoSenhas, "tratamentoSenhas");
        this.chaveTransito = Objects.requireNonNull(chaveTransito, "chaveTransito");
        this.distribuidor = Objects.requireNonNull(distribuidor, "distribuidor");
        this.logAcessos = Objects.requireNonNull(logAcessos, "logAcessos");
        this.enviadorHistorico = Objects.requireNonNull(enviadorHistorico, "enviadorHistorico");
    }

    /** Registra {@code register} e {@code login} no processador (sem limite de requisicoes). */
    public void registrarEm(ProcessadorProtocolo processador) {
        Objects.requireNonNull(processador, "processador");
        processador.registrar(ProtocoloJson.OP_REGISTER, this::registrar);
        processador.registrar(ProtocoloJson.OP_LOGIN, this::login);
    }

    // ------------------------------------------------------------------
    // register
    // ------------------------------------------------------------------

    private void registrar(Requisicao req) throws Exception {
        registrarConexao(req);
        JsonNode dados = decifrarDados(req);
        if (dados == null) {
            return; // ja respondeu
        }

        String login = texto(dados, "login");
        String apelido = texto(dados, "apelido");
        String senha = texto(dados, "senha");
        String avatar = texto(dados, "avatar");
        String cor = texto(dados, "cor");

        // 1) Ausencia, na ordem fixa dos campos.
        String ausente = primeiroAusente(dados);
        if (ausente != null) {
            req.responderErro(CodigoErro.CAMPO_AUSENTE, ausente);
            return;
        }
        // 2) Formato, na mesma ordem. Tipo errado (nao texto) tambem e formato invalido.
        String invalido = primeiroInvalido(login, apelido, senha, avatar, cor);
        if (invalido != null) {
            req.responderErro(CodigoErro.CAMPO_INVALIDO, invalido);
            return;
        }

        NovaConta nova = new NovaConta(
                login,
                apelido.strip(),
                tratamentoSenhas.gerar(senha),
                avatar,
                cor.toUpperCase(Locale.ROOT));
        try {
            contas.criar(nova);
        } catch (LoginDuplicadoException e) {
            req.responderErro(CodigoErro.LOGIN_DUPLICADO);
            return;
        } catch (ServicoIndisponivelException e) {
            req.processador().registrarErro("OperacoesAutenticacao.register", e, req.sessao());
            req.responderErro(CodigoErro.SERVICO_INDISPONIVEL);
            return;
        }
        req.enviar(req.novaRespostaOk().put("login", login));
    }

    /** Nome do primeiro dos cinco campos omitido, nulo ou vazio; {@code null} se nenhum falta. */
    private static String primeiroAusente(JsonNode dados) {
        for (String campo : new String[] {"login", "apelido", "senha", "avatar", "cor"}) {
            JsonNode no = dados.get(campo);
            if (no == null || no.isNull()) {
                return campo;
            }
            if (no.isTextual()) {
                String valor = no.textValue();
                boolean vazio = "apelido".equals(campo) ? valor.isBlank() : valor.isEmpty();
                if (vazio) {
                    return campo;
                }
            }
        }
        return null;
    }

    /** Nome do primeiro campo fora do formato, ou {@code null}. Todos ja estao presentes. */
    private static String primeiroInvalido(
            String login, String apelido, String senha, String avatar, String cor) {
        if (login == null || !PADRAO_LOGIN.matcher(login).matches()) {
            return "login";
        }
        if (apelido == null) {
            return "apelido";
        }
        int pontosApelido = apelido.strip().codePointCount(0, apelido.strip().length());
        if (pontosApelido < 1 || pontosApelido > APELIDO_MAXIMO) {
            return "apelido";
        }
        if (senha == null) {
            return "senha";
        }
        int pontosSenha = senha.codePointCount(0, senha.length());
        if (pontosSenha < SENHA_MINIMA || pontosSenha > SENHA_MAXIMA) {
            return "senha";
        }
        if (avatar == null || !PADRAO_AVATAR.matcher(avatar).matches()) {
            return "avatar";
        }
        if (cor == null || !PADRAO_COR.matcher(cor).matches()) {
            return "cor";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // login
    // ------------------------------------------------------------------

    private void login(Requisicao req) throws Exception {
        Sessao sessao = req.sessao();
        if (sessao.autenticada()) {
            req.responderErro(CodigoErro.JA_AUTENTICADO);
            return;
        }
        registrarConexao(req);

        // Campos opcionais fora do cifrado: validados antes de qualquer trabalho caro.
        JsonNode noDispositivo = req.quadro().get("dispositivo");
        String dispositivo = null;
        if (noDispositivo != null && !noDispositivo.isNull()) {
            if (!noDispositivo.isTextual() || !PADRAO_DISPOSITIVO.matcher(noDispositivo.textValue()).matches()) {
                req.responderErro(CodigoErro.CAMPO_INVALIDO, "dispositivo");
                return;
            }
            dispositivo = noDispositivo.textValue();
        }
        String tipoCliente = null;
        JsonNode noCliente = req.quadro().get("cliente");
        if (noCliente != null && !noCliente.isNull()) {
            JsonNode tipo = noCliente.isObject() ? noCliente.get("tipo") : null;
            if (tipo == null || !tipo.isTextual() || !tipoClienteValido(tipo.textValue())) {
                req.responderErro(CodigoErro.CAMPO_INVALIDO, "cliente");
                return;
            }
            tipoCliente = tipo.textValue();
        }

        JsonNode dados = decifrarDados(req);
        if (dados == null) {
            return; // ja respondeu
        }
        String login = texto(dados, "login");
        String senha = texto(dados, "senha");
        if (dados.get("login") == null || dados.get("login").isNull() || "".equals(login)) {
            req.responderErro(CodigoErro.CAMPO_AUSENTE, "login");
            return;
        }
        if (dados.get("senha") == null || dados.get("senha").isNull() || "".equals(senha)) {
            req.responderErro(CodigoErro.CAMPO_AUSENTE, "senha");
            return;
        }
        if (login == null) {
            req.responderErro(CodigoErro.CAMPO_INVALIDO, "login");
            return;
        }
        if (senha == null) {
            req.responderErro(CodigoErro.CAMPO_INVALIDO, "senha");
            return;
        }

        // Login ou senha fora dos limites do cadastro nao podem existir: evita consulta ao banco e
        // impede que uma senha enorme multiplique o custo do hash. O trabalho do hash continua feito.
        boolean formatoPossivel = PADRAO_LOGIN.matcher(login).matches()
                && senha.codePointCount(0, senha.length()) <= SENHA_MAXIMA;

        Optional<Conta> conta;
        try {
            conta = formatoPossivel ? contas.buscar(login) : Optional.empty();
        } catch (ServicoIndisponivelException e) {
            req.processador().registrarErro("OperacoesAutenticacao.login", e, sessao);
            req.responderErro(CodigoErro.SERVICO_INDISPONIVEL);
            return;
        }
        boolean aceita;
        if (conta.isPresent()) {
            aceita = tratamentoSenhas.verificar(senha, conta.get().senhaHash());
        } else {
            tratamentoSenhas.verificarContaInexistente(formatoPossivel ? senha : "");
            aceita = false;
        }

        if (!aceita) {
            recusar(req, login, dispositivo, tipoCliente);
            return;
        }

        Perfil perfil = conta.get().perfil();
        String idSessao;
        try {
            idSessao = sessao.autenticar(perfil, dispositivo, tipoCliente);
        } catch (IllegalStateException e) {
            req.responderErro(CodigoErro.JA_AUTENTICADO);
            return;
        }
        gravar(new EventoAcesso(
                req.processador().relogio().agora(),
                EventoAcesso.Evento.LOGIN_OK,
                perfil.login(),
                idSessao,
                dispositivo,
                tipoCliente,
                sessao.endereco(),
                null));
        req.enviar(req.novaRespostaOk()
                .put("sessao", idSessao)
                .set("perfil", ProtocoloJson.paraJson(perfil)));

        // Registrar ANTES de ler o historico: mensagens publicadas no intervalo chegam duas vezes
        // (ao vivo e no historico) e o Cliente descarta a duplicata pelo mid; a ordem inversa
        // poderia perder mensagens.
        distribuidor.registrar(sessao);
        enviadorHistorico.enviar(sessao, null);
    }

    private void recusar(Requisicao req, String login, String dispositivo, String tipoCliente) {
        int falhas = req.estado().registrarFalhaAutenticacao();
        gravar(new EventoAcesso(
                req.processador().relogio().agora(),
                EventoAcesso.Evento.LOGIN_RECUSADO,
                PADRAO_LOGIN.matcher(login).matches() ? login : null,
                null,
                dispositivo,
                tipoCliente,
                req.sessao().endereco(),
                null));
        req.responderErro(CodigoErro.CREDENCIAIS_INVALIDAS);
        if (falhas >= FALHAS_PARA_ENCERRAR) {
            // Depois de entregar a resposta que ja esta na fila.
            req.sessao().fecharAposEsvaziar(MOTIVO_FALHAS_DE_LOGIN);
        }
    }

    private static boolean tipoClienteValido(String tipo) {
        return "desktop".equals(tipo) || "web".equals(tipo) || "python".equals(tipo);
    }

    // ------------------------------------------------------------------
    // Log_Acessos
    // ------------------------------------------------------------------

    /**
     * Grava {@code conexao} na primeira chamada para a conexao e arma o registro de
     * {@code desconexao} (com o motivo) para quando a Sessao se encerrar. Chamadas seguintes na mesma
     * conexao nao fazem nada. Pode ser reutilizada por outras operacoes que queiram contar como
     * primeira atividade da conexao.
     */
    public void registrarConexao(Requisicao req) {
        AtomicBoolean registrada = req.estado().atributo(ATRIBUTO_CONEXAO, AtomicBoolean::new);
        if (!registrada.compareAndSet(false, true)) {
            return;
        }
        Sessao sessao = req.sessao();
        ProcessadorProtocolo processador = req.processador();
        gravar(new EventoAcesso(
                processador.relogio().agora(),
                EventoAcesso.Evento.CONEXAO,
                null,
                null,
                null,
                null,
                sessao.endereco(),
                null));
        sessao.aoFechar(motivo -> gravar(new EventoAcesso(
                processador.relogio().agora(),
                EventoAcesso.Evento.DESCONEXAO,
                sessao.perfil().map(Perfil::login).orElse(null),
                sessao.id(),
                sessao.dispositivo(),
                sessao.cliente(),
                sessao.endereco(),
                motivo)));
    }

    /** Grava o evento; falha no log nao interrompe o fluxo e relata so a classe em stderr. */
    private void gravar(EventoAcesso evento) {
        try {
            logAcessos.registrar(evento);
        } catch (Throwable falha) {
            System.err.println("Falha ao gravar no Log_Acessos (" + falha.getClass().getName() + ")");
        }
    }

    // ------------------------------------------------------------------
    // Utilitarios
    // ------------------------------------------------------------------

    /**
     * Decifra e interpreta o campo {@code dados} da requisicao como objeto JSON. Em caso de problema
     * ja responde ao Cliente e devolve {@code null}.
     */
    private JsonNode decifrarDados(Requisicao req) {
        JsonNode no = req.quadro().get("dados");
        if (no == null || no.isNull() || (no.isTextual() && no.textValue().isEmpty())) {
            req.responderErro(CodigoErro.CAMPO_AUSENTE, "dados");
            return null;
        }
        if (!no.isTextual()) {
            req.responderErro(CodigoErro.FORMATO_INVALIDO, "dados");
            return null;
        }
        String claro;
        try {
            claro = CifraManual.decifrar(chaveTransito, no.textValue());
        } catch (DecifragemFalhouException e) {
            // Texto fixo e sem causa: nada recebido vai ao Log_Erros.
            req.processador().registrarErro(
                    "OperacoesAutenticacao." + req.op(),
                    new DecifragemFalhouException("Falha ao decifrar o campo dados"),
                    req.sessao());
            req.responderErro(CodigoErro.DECIFRAGEM_FALHOU);
            return null;
        }
        JsonNode raiz;
        try {
            raiz = MAPEADOR.readTree(claro);
        } catch (JsonProcessingException | RuntimeException e) {
            raiz = null;
        }
        if (raiz == null || !raiz.isObject()) {
            req.responderErro(CodigoErro.FORMATO_INVALIDO, "dados");
            return null;
        }
        return raiz;
    }

    /** Valor textual do campo, ou {@code null} se ausente, nulo ou nao textual. */
    private static String texto(JsonNode objeto, String campo) {
        JsonNode no = objeto.get(campo);
        return no != null && no.isTextual() ? no.textValue() : null;
    }
}
