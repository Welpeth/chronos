package com.chronos.tracker.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.net.URL;

/** Tela de abertura com o logo animado; um clique pula. */
public final class SplashScreen {

    /** Duração da animação (3,6 s) mais uma pausa curta no logo pronto. */
    static final Duration LENGTH = Duration.millis(4200);

    private SplashScreen() {
    }

    /** Mostra o logo animado e chama {@code then} quando ele termina (ou na hora, se a imagem faltar). */
    public static void show(Runnable then) {
        Image gif = load();
        if (gif == null) {
            then.run();
            return;
        }
        ImageView view = new ImageView(gif);
        StackPane root = new StackPane(view);
        root.setStyle("-fx-background-color: transparent;");
        Rectangle clip = new Rectangle(gif.getWidth(), gif.getHeight());
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        view.setClip(clip);

        Stage stage = new Stage(StageStyle.TRANSPARENT);
        Scene scene = new Scene(root, Color.TRANSPARENT);
        stage.setScene(scene);
        stage.setTitle("Chronos");
        AppIcons.applyTo(stage);
        stage.setAlwaysOnTop(true);

        PauseTransition wait = new PauseTransition(LENGTH);
        Runnable finish = new Runnable() {
            private boolean done;

            @Override
            public void run() {
                if (done) {
                    return;
                }
                done = true;
                wait.stop();
                // Fora do pulso da animação: o app pode abrir diálogos com showAndWait.
                Platform.runLater(() -> {
                    then.run();
                    stage.close();
                });
            }
        };
        wait.setOnFinished(event -> finish.run());
        root.setOnMouseClicked(event -> finish.run());

        stage.show();
        stage.centerOnScreen();
        wait.play();
    }

    private static Image load() {
        URL url = SplashScreen.class.getResource("splash.gif");
        if (url == null) {
            return null;
        }
        Image image = new Image(url.toExternalForm());
        return image.isError() ? null : image;
    }
}
