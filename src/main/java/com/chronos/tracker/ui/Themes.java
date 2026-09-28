package com.chronos.tracker.ui;

import javafx.scene.Scene;

/** Tema da janela: o claro (app.css) sempre; o escuro (dark.css) por cima, só trocando as cores. */
public final class Themes {

    private static final String LIGHT = Themes.class.getResource("app.css").toExternalForm();
    private static final String DARK = Themes.class.getResource("dark.css").toExternalForm();

    private Themes() {
    }

    public static void apply(Scene scene, boolean dark) {
        scene.getStylesheets().removeAll(LIGHT, DARK);
        scene.getStylesheets().add(LIGHT);
        if (dark) {
            scene.getStylesheets().add(DARK);
        }
    }
}
