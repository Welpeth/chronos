package com.chronos.tracker.ui;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.net.URL;
import java.util.Arrays;
import java.util.List;

/** Logo do Chronos em vários tamanhos, para a janela, a barra de tarefas e a bandeja do Windows. */
public final class AppIcons {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private AppIcons() {
    }

    /** Arquivo do logo quadrado no tamanho pedido (16, 24, 32, 48, 64, 128 ou 256). */
    public static URL logo(int size) {
        return AppIcons.class.getResource("logo-" + size + ".png");
    }

    public static void applyTo(Stage stage) {
        List<Image> images = Arrays.stream(SIZES)
                .mapToObj(size -> new Image(logo(size).toExternalForm()))
                .toList();
        stage.getIcons().setAll(images);
    }
}
