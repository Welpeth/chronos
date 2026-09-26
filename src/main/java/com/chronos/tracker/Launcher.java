package com.chronos.tracker;

import javafx.application.Application;

/**
 * Ponto de entrada. Fica separado de {@link ChronosApp} para o JAR rodar sem module-path do JavaFX.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(ChronosApp.class, args);
    }
}
