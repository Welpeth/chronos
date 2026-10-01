package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.effect.MotionBlur;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Tela de abertura: o logo do Chronos animado, desenhado em vetor com fundo transparente. O relógio aparece com
 * anéis girando, o anel vira o C, entram as linhas amarelas, o "hronos" desliza de trás do C e uma faixa de luz
 * passa. Um clique pula.
 */
public final class SplashScreen {

    /** Duração da animação mais uma pausa curta no logo pronto, em segundos. */
    static final double LENGTH = 4.1;

    // Geometria do logo pronto, em pixels de desenho (a janela usa SCALE disso).
    private static final double W = 900;
    private static final double H = 260;
    private static final double SCALE = 0.8;
    /** Espaço do "Inicializando, aguarde…" embaixo do logo. */
    private static final double STATUS_HEIGHT = 30;
    private static final double R_OUT = 80;
    private static final double R_IN = 54;
    private static final double CY = 130;
    private static final double CX_FINAL = 205;
    private static final double CX_START = W / 2 + 45;
    private static final double GAP = 38;
    private static final double TEXT_X = CX_FINAL + R_OUT - 4;
    private static final double TEXT_BASE = CY + 52;
    private static final double TEXT_WIDTH = 583;

    private static final Color BLUE = Color.web("#2f86ff");

    private final Group content = new Group();
    private final List<Arc> rings = new ArrayList<>();
    private final Rectangle[] bars = new Rectangle[3];
    private final Group textGroup = new Group();
    private final Text text = new Text("hronos");
    private final MotionBlur textBlur = new MotionBlur(0, 0);
    private final Rectangle sheen = new Rectangle(90, H * 1.6);
    private final Circle face = new Circle();
    private final Group ringHolder = new Group();
    private final Line[] ticks = new Line[4];
    private final Line hourHand = hand(6);
    private final Line minuteHand = hand(6);
    private final Circle hub = new Circle(6, BLUE);
    private final Group clock = new Group();
    private final DropShadow glow = new DropShadow(BlurType.GAUSSIAN, Color.rgb(47, 134, 255, 0.6), 26, 0.12, 0, 0);
    private double textRight;

    SplashScreen() {
        for (int i = 0; i < 4; i++) {
            Arc arc = new Arc();
            arc.setType(ArcType.OPEN);
            arc.setFill(null);
            arc.setStroke(Color.web("#2b74ff"));
            arc.setStrokeWidth(i < 2 ? 4 : 3);
            arc.setStrokeLineCap(StrokeLineCap.ROUND);
            arc.setEffect(new GaussianBlur(1.2));
            rings.add(arc);
        }
        Group barGroup = new Group();
        for (int i = 0; i < bars.length; i++) {
            Rectangle bar = new Rectangle();
            bar.setArcWidth(11);
            bar.setArcHeight(11);
            bar.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.web("#ff9f0a")), new Stop(1, Color.web("#ffe45c"))));
            bars[i] = bar;
            barGroup.getChildren().add(bar);
        }

        text.setFont(Font.font("Segoe UI", FontWeight.BOLD, 150));
        // Fontes variam por sistema: ajusta o tamanho para o texto ocupar a mesma largura do logo.
        double measured = text.getLayoutBounds().getWidth();
        if (measured > 0) {
            text.setFont(Font.font("Segoe UI", FontWeight.BOLD, 150 * TEXT_WIDTH / measured));
        }
        text.setTextOrigin(VPos.BASELINE);
        text.setX(TEXT_X);
        text.setY(TEXT_BASE);
        text.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE), new Stop(1, Color.web("#bfcdf3"))));
        // Contorno escuro suave: o texto branco continua legível sobre um papel de parede claro.
        textBlur.setInput(new DropShadow(BlurType.GAUSSIAN, Color.rgb(4, 10, 31, 0.7), 8, 0.45, 0, 2));
        text.setEffect(textBlur);
        textRight = TEXT_X + text.getLayoutBounds().getWidth();

        // Faixa de luz: só aparece por cima das letras.
        sheen.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 255, 255, 0)), new Stop(0.5, Color.rgb(255, 255, 255, 0.6)),
                new Stop(1, Color.rgb(255, 255, 255, 0))));
        sheen.getTransforms().add(new Rotate(20, 45, H * 0.8));
        sheen.setY(-H * 0.3);
        Group sheenHolder = new Group(sheen);
        Text clip = new Text("hronos");
        clip.setFont(text.getFont());
        clip.setTextOrigin(VPos.BASELINE);
        clip.setX(TEXT_X);
        clip.setY(TEXT_BASE);
        sheenHolder.setClip(clip);
        textGroup.getChildren().addAll(text, sheenHolder);

        face.setCenterY(CY);
        face.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0d1d4a")), new Stop(1, Color.web("#081333"))));
        for (int i = 0; i < ticks.length; i++) {
            ticks[i] = hand(4);
        }
        clock.getChildren().addAll(ticks);
        clock.getChildren().addAll(hourHand, minuteHand, hub);

        Group rings = new Group();
        rings.getChildren().addAll(this.rings);
        content.getChildren().addAll(rings, barGroup, textGroup, face, ringHolder, clock);
        content.setEffect(glow);
        update(0);
    }

    private static Line hand(double width) {
        Line line = new Line();
        line.setStroke(BLUE);
        line.setStrokeWidth(width);
        line.setStrokeLineCap(StrokeLineCap.ROUND);
        return line;
    }

    /** Mostra a abertura e chama {@code then} quando ela termina ou quando clicam nela. */
    public static void show(Runnable then) {
        show(CompletableFuture.completedFuture(null), then);
    }

    /**
     * Mostra a abertura com "Inicializando, aguarde…" e chama {@code then} quando a animação terminou (ou
     * clicaram) e {@code ready} concluiu. Enquanto {@code ready} não conclui, o logo fica pronto na tela.
     */
    public static void show(CompletableFuture<?> ready, Runnable then) {
        SplashScreen splash;
        try {
            splash = new SplashScreen();
        } catch (RuntimeException e) {
            then.run();
            return;
        }
        Text status = new Text();
        status.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 15));
        status.setFill(Color.web("#dce6ff"));
        status.setEffect(new DropShadow(BlurType.GAUSSIAN, Color.rgb(4, 10, 31, 0.85), 6, 0.5, 0, 1));
        status.setTextOrigin(VPos.TOP);
        status.setY(H * SCALE + 4);
        status.setX(TEXT_X * SCALE);
        status.setOpacity(0);

        Pane root = new Pane(splash.content, status);
        // Quase invisível: só para o clique pegar também entre as letras.
        root.setStyle("-fx-background-color: rgba(0,0,0,0.004);");
        splash.content.getTransforms().add(new Scale(SCALE, SCALE));
        Scene scene = new Scene(root, W * SCALE, H * SCALE + STATUS_HEIGHT, Color.TRANSPARENT);

        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.setScene(scene);
        stage.setTitle("Chronos");
        AppIcons.applyTo(stage);
        stage.setAlwaysOnTop(true);

        String waiting = I18n.t("Inicializando, aguarde");
        var timer = new AnimationTimer() {
            private long start = -1;
            private boolean skipped;
            private boolean done;

            @Override
            public void handle(long now) {
                if (start < 0) {
                    start = now;
                }
                double t = (now - start) / 1e9;
                splash.update(skipped ? 3.6 : Math.min(t, 3.6));
                status.setOpacity(seg(t, 0.3, 0.8));
                status.setText(waiting + ".".repeat(1 + (int) (t * 2.5) % 3));
                if ((skipped || t >= LENGTH) && ready.isDone()) {
                    finish();
                }
            }

            void skip() {
                skipped = true;
            }

            void finish() {
                if (done) {
                    return;
                }
                done = true;
                stop();
                // Fora do pulso da animação: o app pode abrir diálogos com showAndWait.
                Platform.runLater(() -> {
                    then.run();
                    stage.close();
                });
            }
        };
        root.setOnMouseClicked(event -> timer.skip());

        stage.show();
        stage.centerOnScreen();
        timer.start();
    }

    /** Desenha o quadro do instante {@code t} (segundos). */
    void update(double t) {
        double move = easeInOut(seg(t, 1.55, 2.35));
        double cx = CX_START + (CX_FINAL - CX_START) * move;
        double dx = cx - CX_FINAL;

        double appear = seg(t, 0, 0.55);
        double scale = appear < 1 ? 0.55 + 0.45 * easeOutBack(appear) : 1;
        double morph = easeInOut(seg(t, 0.65, 1.25));
        double rIn = R_IN * scale;
        double rOut = (R_IN + 7 + (R_OUT - R_IN - 7) * morph) * scale;
        double gap = GAP * morph;
        double alpha = easeOutCubic(appear);

        // Anéis girando em volta do relógio, somem enquanto o anel vira C.
        double ringsAlpha = alpha * (1 - seg(t, 0.95, 1.5));
        double[][] spec = {{R_OUT + 20, 110, 260, 0, 0.8}, {R_OUT + 34, 70, -200, 40, 0.55}};
        for (int i = 0; i < 2; i++) {
            double radius = spec[i][0] * (0.85 + 0.15 * scale);
            for (int k = 0; k < 2; k++) {
                Arc arc = rings.get(i * 2 + k);
                arc.setCenterX(cx);
                arc.setCenterY(CY);
                arc.setRadiusX(radius);
                arc.setRadiusY(radius);
                arc.setStartAngle(-(spec[i][3] + k * 180 + spec[i][2] * t) - spec[i][1]);
                arc.setLength(spec[i][1]);
                arc.setOpacity(ringsAlpha * spec[i][4]);
                arc.setVisible(ringsAlpha > 0.01);
            }
        }

        // Linhas de velocidade.
        double[][] barSpec = {{-24, 72, 0, 6}, {-2, 108, 0.07, 6}, {20, 52, 0.14, -12}};
        for (int i = 0; i < bars.length; i++) {
            double p = easeOutCubic(seg(t, 1.15 + barSpec[i][2], 1.6 + barSpec[i][2]));
            double right = cx - R_OUT + barSpec[i][3] - 40 * (1 - p);
            double left = right - barSpec[i][1] * p;
            Rectangle bar = bars[i];
            bar.setX(left);
            bar.setY(CY + barSpec[i][0] - 5.5);
            bar.setWidth(Math.max(11, right - left));
            bar.setHeight(11);
            bar.setOpacity(p);
            bar.setVisible(p > 0);
        }

        // "hronos" desliza de trás do C, com rastro.
        double tp = seg(t, 1.75, 2.55);
        double offset = -150 * (1 - easeOutCubic(tp)) + dx;
        textGroup.setTranslateX(offset);
        textGroup.setOpacity(clamp(tp * 2.2));
        textGroup.setVisible(tp > 0);
        double speed = 150 * 3 * (1 - tp) * (1 - tp) / 0.8 / 60;
        textBlur.setRadius(Math.min(63, speed * 1.5));

        // Mostrador escuro e C azul.
        face.setCenterX(cx);
        face.setRadius(rIn);
        face.setOpacity(alpha);
        Arc outer = new Arc(cx, CY, rOut, rOut, gap, 360 - 2 * gap);
        outer.setType(ArcType.ROUND);
        Shape ring = Shape.subtract(outer, new Circle(cx, CY, rIn));
        ring.setFill(new LinearGradient(0, CY - rOut, 0, CY + rOut, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#5aa8ff")), new Stop(1, Color.web("#1248d8"))));
        ring.setOpacity(alpha);
        ringHolder.getChildren().setAll(ring);

        // Marcações e ponteiros girando até a posição final.
        int[] angles = {90, 0, 270, 180};
        for (int i = 0; i < ticks.length; i++) {
            double a = Math.toRadians(angles[i]);
            ticks[i].setStartX(cx + rIn * 0.72 * Math.cos(a));
            ticks[i].setStartY(CY - rIn * 0.72 * Math.sin(a));
            ticks[i].setEndX(cx + rIn * 0.9 * Math.cos(a));
            ticks[i].setEndY(CY - rIn * 0.9 * Math.sin(a));
        }
        double spin = 1 - easeOutCubic(seg(t, 0, 1.3));
        point(hourHand, cx, rIn * 0.48, 148 + 360 * spin);
        point(minuteHand, cx, rIn * 0.66, 38 + 720 * spin);
        hub.setCenterX(cx);
        hub.setCenterY(CY);
        clock.setOpacity(alpha);

        // Faixa de luz atravessando o texto.
        double sp = seg(t, 2.75, 3.5);
        double from = cx - R_OUT - 80;
        double x0 = from + (textRight + dx + 120 - from) * easeInOut(sp);
        sheen.setX(x0 - offset - 45);
        sheen.setVisible(sp > 0 && sp < 1);

        double pulse = 0.55 + 0.45 * Math.sin(Math.PI * seg(t, 2.35, 3.0))
                + 0.25 * Math.sin(Math.PI * seg(t, 3.0, 3.6));
        glow.setColor(Color.rgb(47, 134, 255, clamp(0.55 * pulse * alpha)));
        glow.setRadius(16 + 14 * pulse);
    }

    private static void point(Line line, double cx, double length, double degrees) {
        double a = Math.toRadians(degrees);
        line.setStartX(cx);
        line.setStartY(CY);
        line.setEndX(cx + length * Math.cos(a));
        line.setEndY(CY - length * Math.sin(a));
    }

    static double clamp(double x) {
        return Math.max(0, Math.min(1, x));
    }

    static double seg(double t, double a, double b) {
        return clamp((t - a) / (b - a));
    }

    static double easeOutCubic(double x) {
        return 1 - Math.pow(1 - x, 3);
    }

    static double easeInOut(double x) {
        return 3 * x * x - 2 * x * x * x;
    }

    static double easeOutBack(double x) {
        double k = 1.6;
        return 1 + (k + 1) * Math.pow(x - 1, 3) + k * Math.pow(x - 1, 2);
    }

    /** O desenho, para testes. */
    Node content() {
        return content;
    }
}
