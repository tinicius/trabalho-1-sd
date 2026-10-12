package br.cefet.sd.middleware.contas;

import java.util.Optional;

/**
 * Porta de acesso as Contas. A implementacao de producao usa PostgreSQL via JDBC; os testes usam
 * uma implementacao em memoria.
 */
public interface ContasRepositorio {

    /**
     * Cria a Conta. A unicidade do login e garantida pelo repositorio (sem consulta previa), o que
     * evita corrida entre dois cadastros simultaneos.
     *
     * @throws LoginDuplicadoException se ja existe Conta com o mesmo login
     * @throws ServicoIndisponivelException se o Banco_de_Dados esta inacessivel
     */
    void criar(NovaConta conta) throws LoginDuplicadoException;

    /**
     * Busca uma Conta pelo login.
     *
     * @return a Conta, ou vazio se nao existe
     * @throws ServicoIndisponivelException se o Banco_de_Dados esta inacessivel
     */
    Optional<Conta> buscar(String login);
}
