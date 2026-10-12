package br.cefet.sd.middleware.protocolo;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Estado de protocolo mantido pelo {@link ProcessadorProtocolo} para cada {@code Sessao} (conexao).
 * Fica no processador, e nao na {@code Sessao}, para que as operacoes tenham onde guardar contadores
 * sem alterar a interface da Sessao. E descartado quando a Sessao se encerra.
 */
public final class EstadoConexao {

    private final AtomicInteger falhasAutenticacao = new AtomicInteger();
    private final Map<String, LimitadorTaxa> limitadores = new ConcurrentHashMap<>();
    private final Map<String, Object> atributos = new ConcurrentHashMap<>();

    /** Falhas de autenticacao nesta conexao (a quinta encerra a conexao; usado por {@code login}). */
    public int falhasAutenticacao() {
        return falhasAutenticacao.get();
    }

    /** Conta mais uma falha de autenticacao e devolve o total. */
    public int registrarFalhaAutenticacao() {
        return falhasAutenticacao.incrementAndGet();
    }

    /** Limitador desta conexao para a operacao {@code op}, criado no primeiro uso. */
    LimitadorTaxa limitador(String op, LimiteRequisicoes limite) {
        return limitadores.computeIfAbsent(op, o -> new LimitadorTaxa(limite));
    }

    /**
     * Atributo livre por conexao (por exemplo, o controle de {@code conexao} ja registrada no
     * Log_Acessos). Cria o valor na primeira chamada para a chave.
     */
    @SuppressWarnings("unchecked")
    public <T> T atributo(String chave, Supplier<T> criar) {
        Objects.requireNonNull(chave, "chave");
        return (T) atributos.computeIfAbsent(chave, k -> criar.get());
    }
}
