package br.cefet.sd.comum.protocolo;

/** Transporte pelo qual uma Sessao esta conectada ao Middleware. */
public enum TipoTransporte {
    /** Socket TCP (porta 9000): uma mensagem JSON por linha. */
    TCP,
    /** WebSocket (porta 9001): um frame de texto por mensagem. */
    WEBSOCKET
}
