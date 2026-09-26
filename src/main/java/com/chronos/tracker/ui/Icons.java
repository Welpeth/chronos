package com.chronos.tracker.ui;

import javafx.scene.Group;
import javafx.scene.shape.SVGPath;

/** Ícones desenhados como SVG (grade de 24×24), coloridos via CSS com a classe {@code icon}. */
public final class Icons {

    public static final String CLOCK = "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zm0 2a8 8 0 1 1 0 16a8 8 0 1 1 0-16z"
            + "M11 6h2v5.4l3.8 2.2-1 1.7L11 12.5z";
    public static final String HOME = "M12 3 2 11.5h3V21h5.5v-6h3v6H19v-9.5h3z";
    public static final String LIST = "M3 5h3v3H3zm5 .5h13v2H8zM3 10.5h3v3H3zm5 .5h13v2H8zM3 16h3v3H3zm5 .5h13v2H8z";
    public static final String PLAY = "M7 4.5v15l12.5-7.5z";
    public static final String PAUSE = "M6 4h4.5v16H6zm7.5 0H18v16h-4.5z";
    public static final String MONITOR = "M2 4h20v13H2zm2 2v9h16V6zM8 19h8v2H8z";
    public static final String DIAMOND = "M12 1.5 22.5 12 12 22.5 1.5 12zm0 6.5L8 12l4 4 4-4z";
    public static final String CHART = "M3 20V11h3.5v9zm6.25 0V4h3.5v16zM15.5 20v-6H19v6z";
    public static final String CHECK_CIRCLE = "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zm0 2a8 8 0 1 1 0 16a8 8 0 1 1 0-16z"
            + "M10.5 13.6 7.9 11l-1.4 1.4 4 4 7-7-1.4-1.4z";
    public static final String CIRCLE = "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zm0 2a8 8 0 1 1 0 16a8 8 0 1 1 0-16z";

    private Icons() {
    }

    /** O ícone já dentro de um {@link Group}, para o layout enxergar o tamanho depois da escala. */
    public static Group of(String path, double size, String... styleClasses) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("icon");
        icon.getStyleClass().addAll(styleClasses);
        double scale = size / 24.0;
        icon.setScaleX(scale);
        icon.setScaleY(scale);
        return new Group(icon);
    }
}
