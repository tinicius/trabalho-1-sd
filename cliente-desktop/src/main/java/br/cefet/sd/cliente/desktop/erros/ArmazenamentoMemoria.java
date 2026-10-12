package br.cefet.sd.cliente.desktop.erros;

/** Armazenamento em memoria (testes e fallback). */
public final class ArmazenamentoMemoria implements ArmazenamentoErros {

    private Estado estado = Estado.vazio();

    @Override
    public synchronized Estado carregar() {
        return estado;
    }

    @Override
    public synchronized void gravar(Estado novo) {
        this.estado = new Estado(novo.eventos(), novo.descartados());
    }
}
