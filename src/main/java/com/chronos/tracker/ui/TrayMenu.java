package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Menu do botão direito no ícone da bandeja, no visual do app: as tasks que estão contando, com o tempo,
 * Pausar e Finalizar, e Abrir, Pausar todas e Sair. Substitui o menu cinza do Windows.
 *
 * <p>É uma janela sem borda, sem botão na barra de tarefas, que some ao perder o foco, com Esc ou quando o mouse
 * sai dela por um tempo.
 */
public final class TrayMenu {

    /** Espaço da sombra em volta do cartão. */
    static final double SHADOW = 12;
    private static final double WIDTH = 330;

    private final TrayIconController.Actions actions;
    private final BooleanSupplier dark;
    private final Stage owner = new Stage(StageStyle.UTILITY);
    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final VBox card = new VBox();
    private final PauseTransition leave = new PauseTransition(Duration.seconds(2.5));
    private List<TaskView> running = List.of();

    public TrayMenu(TrayIconController.Actions actions, BooleanSupplier dark) {
        this.actions = actions;
        this.dark = dark;
        // Dono invisível: assim o menu não ganha botão na barra de tarefas.
        owner.setOpacity(0);
        owner.setWidth(1);
        owner.setHeight(1);
        stage.initOwner(owner);
        stage.setAlwaysOnTop(true);

        card.getStyleClass().add("tray-menu");
        card.setPrefWidth(WIDTH);
        StackPane root = new StackPane(card);
        root.getStyleClass().add("tray-menu-area");
        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
            }
        });
        stage.setScene(scene);
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                hide();
            }
        });
        leave.setOnFinished(e -> hide());
        root.setOnMouseExited(e -> leave.playFromStart());
        root.setOnMouseEntered(e -> leave.stop());
    }

    /** Guarda as tasks que estão contando; com o menu aberto, redesenha. Pode vir de qualquer thread. */
    public void update(Snapshot snapshot) {
        List<TaskView> now = snapshot.tasks().stream().filter(TaskView::running).toList();
        runOnFx(() -> {
            running = now;
            if (stage.isShowing()) {
                build();
            }
        });
    }

    /** Abre o menu junto ao ponto da tela onde foi o clique. Pode vir da thread do AWT. */
    public void show(double x, double y) {
        runOnFx(() -> {
            Themes.apply(stage.getScene(), dark.getAsBoolean());
            build();
            if (!owner.isShowing()) {
                owner.show();
            }
            stage.show();
            stage.sizeToScene();
            Rectangle2D bounds = Screen.getScreensForRectangle(x, y, 1, 1).stream().findFirst()
                    .orElse(Screen.getPrimary()).getVisualBounds();
            double[] at = place(x, y, stage.getWidth(), stage.getHeight(), bounds);
            stage.setX(at[0]);
            stage.setY(at[1]);
            stage.toFront();
            stage.requestFocus();
            leave.stop();
        });
    }

    public void hide() {
        leave.stop();
        stage.hide();
        owner.hide();
    }

    /**
     * Onde a janela do menu fica: acima do clique (barra de tarefas embaixo) ou abaixo (barra em cima), sempre
     * dentro da área livre da tela. Desconta a sombra para o cartão encostar no ponto do clique.
     */
    static double[] place(double x, double y, double width, double height, Rectangle2D bounds) {
        double left = x - width + SHADOW;
        if (left < bounds.getMinX()) {
            left = x - SHADOW;
        }
        left = Math.max(bounds.getMinX(), Math.min(left, bounds.getMaxX() - width));
        double top = y - height + SHADOW;
        if (top < bounds.getMinY()) {
            top = y - SHADOW;
        }
        top = Math.max(bounds.getMinY(), Math.min(top, bounds.getMaxY() - height));
        return new double[] {left, top};
    }

    private void build() {
        card.getChildren().clear();

        ImageView logo = new ImageView(AppIcons.logo(32).toExternalForm());
        logo.setFitWidth(28);
        logo.setFitHeight(28);
        Label name = new Label("Chronos");
        name.getStyleClass().add("tray-menu-title");
        Label status = new Label(status(running.size()));
        status.getStyleClass().add("tray-menu-status");
        HBox header = new HBox(10, logo, new VBox(1, name, status));
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("tray-menu-header");
        card.getChildren().add(header);

        if (!running.isEmpty()) {
            VBox tasks = new VBox(2);
            tasks.getStyleClass().add("tray-menu-tasks");
            running.forEach(task -> tasks.getChildren().add(taskRow(task)));
            card.getChildren().add(tasks);
        }

        card.getChildren().add(divider());
        Button pauseAll = item(Icons.PAUSE, I18n.t("Pausar todas"), actions::pauseAll);
        pauseAll.setDisable(running.isEmpty());
        card.getChildren().addAll(item(Icons.HOME, I18n.t("Abrir o Chronos"), actions::open), pauseAll, divider());
        Button exit = item(Icons.CLOSE, I18n.t("Sair"), actions::exit);
        exit.getStyleClass().add("tray-menu-exit");
        card.getChildren().add(exit);
    }

    static String status(int running) {
        return switch (running) {
            case 0 -> I18n.t("Nenhuma task contando");
            case 1 -> I18n.t("1 task contando");
            default -> I18n.t("{0} tasks contando", running);
        };
    }

    private Node taskRow(TaskView task) {
        Label key = new Label(task.key());
        key.getStyleClass().add("tray-menu-key");
        Label summary = new Label(task.summary());
        summary.getStyleClass().add("tray-menu-summary");
        summary.setMinWidth(0);
        VBox text = new VBox(1, key, summary);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label time = new Label(Formats.hms(task.totalTime()));
        time.getStyleClass().add("tray-menu-time");
        time.setMinWidth(Region.USE_PREF_SIZE);
        Button pause = iconButton(Icons.PAUSE, I18n.t("Pausar"), () -> actions.pause(task.key()));
        Button finish = iconButton(Icons.CHECK_CIRCLE, I18n.t("Finalizar (mover para Concluído)"),
                () -> actions.finish(task.key()));
        HBox row = new HBox(8, text, time, pause, finish);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("tray-menu-task");
        return row;
    }

    private Button item(String icon, String text, Runnable action) {
        Button button = new Button(text, Icons.of(icon, 16, "tray-menu-icon"));
        button.getStyleClass().add("tray-menu-item");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setGraphicTextGap(10);
        button.setOnAction(e -> {
            hide();
            action.run();
        });
        return button;
    }

    private Button iconButton(String icon, String tip, Runnable action) {
        Button button = new Button(null, Icons.of(icon, 16, "tray-menu-icon"));
        button.getStyleClass().add("tray-menu-icon-button");
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(e -> {
            hide();
            action.run();
        });
        return button;
    }

    private static Region divider() {
        Region line = new Region();
        line.getStyleClass().add("tray-menu-divider");
        return line;
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
