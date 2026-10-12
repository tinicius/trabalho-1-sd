package br.cefet.sd.middleware.logs;

/** Porta do Log_Erros ({@code dados/logs/erros.txt}), um objeto JSON por linha. */
public interface LogErros {

    /**
     * Grava o evento, aplicando o mascaramento a {@code descricao} e {@code pilha}.
     * Nao lanca excecao: se a escrita falhar, a implementacao relata em {@code stderr} sem
     * gerar novo registro no Log_Erros, evitando recursao.
     */
    void registrar(EventoErro evento);
}
