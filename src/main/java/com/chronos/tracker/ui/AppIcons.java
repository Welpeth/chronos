package com.chronos.tracker.ui;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Logo do Chronos em vários tamanhos, para a janela, a barra de tarefas e a bandeja do Windows. */
public final class AppIcons {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static final Color BADGE_RED = new Color(0xE5, 0x39, 0x35);

    private AppIcons() {
    }

    /** Arquivo do logo quadrado no tamanho pedido (16, 24, 32, 48, 64, 128 ou 256). */
    public static URL logo(int size) {
        return AppIcons.class.getResource("logo-" + size + ".png");
    }

    public static void applyTo(Stage stage) {
        applyTo(stage, false);
    }

    /** Ícones da janela (e da barra de tarefas), com a bolinha vermelha de aviso quando {@code badge}. */
    public static void applyTo(Stage stage, boolean badge) {
        List<Image> images = new ArrayList<>();
        for (int size : SIZES) {
            images.add(badge ? toFx(withBadge(read(size))) : new Image(logo(size).toExternalForm()));
        }
        stage.getIcons().setAll(images);
    }

    /** Logo pronto para o AWT (bandeja), com ou sem a bolinha. */
    public static BufferedImage awtLogo(int size, boolean badge) {
        BufferedImage image = read(size);
        return badge ? withBadge(image) : image;
    }

    /** Cópia da imagem com uma bolinha vermelha no canto superior direito. */
    static BufferedImage withBadge(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, null);
            int diameter = Math.max(6, Math.round(Math.min(width, height) * 0.45f));
            int x = width - diameter;
            int border = Math.max(1, diameter / 8);
            g.setColor(Color.WHITE);
            g.fillOval(x, 0, diameter, diameter);
            g.setColor(BADGE_RED);
            g.fillOval(x + border, border, diameter - 2 * border, diameter - 2 * border);
        } finally {
            g.dispose();
        }
        return result;
    }

    private static BufferedImage read(int size) {
        try {
            BufferedImage image = ImageIO.read(logo(size));
            BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
            return argb;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Image toFx(BufferedImage image) {
        WritableImage fx = new WritableImage(image.getWidth(), image.getHeight());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                fx.getPixelWriter().setArgb(x, y, image.getRGB(x, y));
            }
        }
        return fx;
    }
}
