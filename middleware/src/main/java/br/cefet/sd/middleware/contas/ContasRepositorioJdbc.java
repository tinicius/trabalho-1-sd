package br.cefet.sd.middleware.contas;

import br.cefet.sd.middleware.config.Configuracao;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * {@link ContasRepositorio} sobre PostgreSQL via JDBC.
 *
 * <p>Regras de projeto:
 * <ul>
 *   <li>somente {@link PreparedStatement}, com parametros ligados (sem concatenar SQL);
 *   <li>uma conexao por operacao, aberta e fechada dentro de cada chamada (cadastro e login sao
 *       raros);
 *   <li>URL, usuario e senha vem de {@link Configuracao}, apenas no lado servidor;
 *   <li>a unicidade do login e garantida pela restricao {@code UNIQUE}: a violacao (SQLState
 *       {@value #SQLSTATE_VIOLACAO_UNICIDADE}) vira {@link LoginDuplicadoException}, sem consulta
 *       previa;
 *   <li>qualquer outra falha de JDBC (conexao, consulta, restricao diferente da unicidade) vira
 *       {@link ServicoIndisponivelException}, que o Middleware converte em
 *       {@code SERVICO_INDISPONIVEL}.
 * </ul>
 */
public final class ContasRepositorioJdbc implements ContasRepositorio {

    /** SQLState de {@code unique_violation} do PostgreSQL. */
    static final String SQLSTATE_VIOLACAO_UNICIDADE = "23505";

    /** Tempo maximo, em segundos, para abrir a conexao (pgjdbc {@code connectTimeout}). */
    private static final String TEMPO_CONEXAO_S = "5";

    /** Tempo maximo, em segundos, de espera por resposta (pgjdbc {@code socketTimeout}). */
    private static final String TEMPO_SOCKET_S = "10";

    private static final String SQL_INSERIR =
            "INSERT INTO contas (login, apelido, senha_hash, avatar, cor) VALUES (?, ?, ?, ?, ?)";

    private static final String SQL_BUSCAR =
            "SELECT login, apelido, senha_hash, avatar, cor FROM contas WHERE login = ?";

    /** Abre uma conexao nova; cada operacao chama uma vez e fecha ao terminar. */
    @FunctionalInterface
    interface FabricaConexao {
        Connection abrir() throws SQLException;
    }

    private final FabricaConexao fabrica;

    /**
     * Cria o repositorio com as credenciais da configuracao do servidor.
     *
     * @param configuracao fonte de {@code db.url}, {@code db.usuario} e {@code db.senha}
     */
    public ContasRepositorioJdbc(Configuracao configuracao) {
        this(fabricaDe(Objects.requireNonNull(configuracao, "configuracao")));
    }

    /** Uso interno e de testes: conexao fornecida por uma fabrica. */
    ContasRepositorioJdbc(FabricaConexao fabrica) {
        this.fabrica = Objects.requireNonNull(fabrica, "fabrica");
    }

    private static FabricaConexao fabricaDe(Configuracao configuracao) {
        String url = configuracao.dbUrl();
        Properties propriedades = new Properties();
        propriedades.setProperty("user", configuracao.dbUsuario());
        propriedades.setProperty("password", configuracao.dbSenha());
        propriedades.setProperty("connectTimeout", TEMPO_CONEXAO_S);
        propriedades.setProperty("socketTimeout", TEMPO_SOCKET_S);
        return () -> DriverManager.getConnection(url, propriedades);
    }

    @Override
    public void criar(NovaConta conta) throws LoginDuplicadoException {
        Objects.requireNonNull(conta, "conta");
        try (Connection conexao = fabrica.abrir();
                PreparedStatement comando = conexao.prepareStatement(SQL_INSERIR)) {
            comando.setString(1, conta.login());
            comando.setString(2, conta.apelido());
            comando.setString(3, conta.senhaHash());
            comando.setString(4, conta.avatar());
            comando.setString(5, conta.cor());
            comando.executeUpdate();
        } catch (SQLException e) {
            if (violacaoDeUnicidade(e)) {
                throw new LoginDuplicadoException(e);
            }
            throw new ServicoIndisponivelException(e);
        }
    }

    @Override
    public Optional<Conta> buscar(String login) {
        Objects.requireNonNull(login, "login");
        try (Connection conexao = fabrica.abrir();
                PreparedStatement comando = conexao.prepareStatement(SQL_BUSCAR)) {
            comando.setString(1, login);
            try (ResultSet resultado = comando.executeQuery()) {
                if (!resultado.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Conta(
                        resultado.getString("login"),
                        resultado.getString("apelido"),
                        resultado.getString("senha_hash"),
                        resultado.getString("avatar"),
                        resultado.getString("cor")));
            }
        } catch (SQLException e) {
            throw new ServicoIndisponivelException(e);
        }
    }

    /** {@code true} se a excecao (ou alguma encadeada) traz o SQLState 23505. */
    private static boolean violacaoDeUnicidade(SQLException erro) {
        for (SQLException atual = erro; atual != null; atual = atual.getNextException()) {
            if (SQLSTATE_VIOLACAO_UNICIDADE.equals(atual.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
