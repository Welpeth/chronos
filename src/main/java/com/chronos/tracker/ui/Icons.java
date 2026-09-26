package com.chronos.tracker.ui;

import javafx.scene.Group;
import javafx.scene.shape.FillRule;
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
    public static final String CLIPBOARD_CHECK = "M5 4h14v18H5zM7 6h10v14H7zM9 2h6v2H9z"
            + "M10.6 15.8 8 13.2l1.2-1.2 1.4 1.4 3.8-3.8 1.2 1.2z";
    public static final String CALENDAR = "M4 5h16v16H4zM6 10h12v9H6zM7 2.5h2V5H7zM15 2.5h2V5h-2z";
    public static final String NOTE = "M5 2h9l5 5v15H5zM7 4h6v4h4v12H7zM9 12h6v1.6H9zM9 15.5h6v1.6H9z";
    public static final String INFO = "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zm0 2a8 8 0 1 1 0 16a8 8 0 1 1 0-16z"
            + "M11 10h2v7h-2zM11 6.5h2v2h-2z";
    public static final String CLOSE = "M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z";
    public static final String PLUS = "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z";

    private Icons() {
    }

    /** O ícone já dentro de um {@link Group}, para o layout enxergar o tamanho depois da escala. */
    public static Group of(String path, double size, String... styleClasses) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.setFillRule(FillRule.EVEN_ODD);
        icon.getStyleClass().add("icon");
        icon.getStyleClass().addAll(styleClasses);
        double scale = size / 24.0;
        icon.setScaleX(scale);
        icon.setScaleY(scale);
        return new Group(icon);
    }
}
