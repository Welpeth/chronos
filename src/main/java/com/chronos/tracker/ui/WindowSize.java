package com.chronos.tracker.ui;

/**
 * Tamanho da janela principal para a área livre da tela (sem a barra de tarefas). Em telas menores que o
 * tamanho normal, como um notebook com zoom de 125% ou 150% no Windows, a janela abre maximizada e o tamanho
 * mínimo cabe na tela, para os botões de fechar e minimizar nunca ficarem de fora.
 */
public record WindowSize(double width, double height, double minWidth, double minHeight, boolean maximized) {

    static final double WIDTH = 1320;
    static final double HEIGHT = 860;
    static final double MIN_WIDTH = 1100;
    static final double MIN_HEIGHT = 700;

    public static WindowSize fit(double screenWidth, double screenHeight) {
        boolean small = screenWidth < WIDTH || screenHeight < HEIGHT;
        return new WindowSize(
                Math.min(WIDTH, screenWidth),
                Math.min(HEIGHT, screenHeight),
                Math.min(MIN_WIDTH, screenWidth),
                Math.min(MIN_HEIGHT, screenHeight),
                small);
    }
}
