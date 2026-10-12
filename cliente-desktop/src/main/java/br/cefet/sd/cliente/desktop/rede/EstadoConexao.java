package br.cefet.sd.cliente.desktop.rede;

/** Estados da conexao informados a interface. */
public enum EstadoConexao {
    /** Sem conexao (inicial, apos logout, falha ao abrir ou tentativas esgotadas). */
    DESCONECTADO,
    /** Abrindo o socket pela primeira vez. */
    CONECTANDO,
    /** Socket aberto, sem Sessao autenticada. */
    CONECTADO,
    /** Login confirmado pelo Middleware. */
    AUTENTICADO,
    /** Conexao perdida; tentando de novo com backoff. */
    RECONECTANDO
}
