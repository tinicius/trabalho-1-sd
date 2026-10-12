package br.cefet.sd.cliente.desktop.ui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;

/** Redimensionamento de imagens para icones (funciona sem display). */
final class Imagens {

    private Imagens() {}

    /**
     * Icone com a imagem ajustada a uma caixa {@code largura x altura}, mantendo a proporcao. Para
     * origem quadrada e caixa quadrada, o resultado tem exatamente o tamanho da caixa.
     */
    static ImageIcon icone(BufferedImage origem, int largura, int altura) {
        double escala = Math.min((double) largura / origem.getWidth(), (double) altura / origem.getHeight());
        int w = Math.max(1, (int) Math.round(origem.getWidth() * escala));
        int h = Math.max(1, (int) Math.round(origem.getHeight() * escala));
        BufferedImage destino = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = destino.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(origem, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return new ImageIcon(destino);
    }
}
