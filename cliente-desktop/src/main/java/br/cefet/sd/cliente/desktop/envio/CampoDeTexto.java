package br.cefet.sd.cliente.desktop.envio;

/** Abstracao do campo de texto da interface (na GUI, um {@code JTextField}); permite testar sem Swing. */
public interface CampoDeTexto {
    /** Texto atual do campo. */
    String ler();

    /** Substitui o texto do campo. */
    void escrever(String texto);
}
