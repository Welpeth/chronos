package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.tracking.TaskView;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/** Linhas da lista de tarefas: chave, título, status, tempo e o botão de play/pausa. */
public final class TaskRows {

    private TaskRows() {
    }

    public static HBox row(TaskView task, Consumer<TaskView> onToggle) {
        return row(task, onToggle, List.of());
    }

    /** A linha com os quadros do Jira em que a task está, abaixo do título. */
    public static HBox row(TaskView task, Consumer<TaskView> onToggle, List<String> boards) {
        return row(task, onToggle, boards, false);
    }

    /** Com {@code showUpdated}, mostra também quando a task foi modificada no Jira. */
    public static HBox row(TaskView task, Consumer<TaskView> onToggle, List<String> boards, boolean showUpdated) {
        Label key = new Label(task.key());
        key.getStyleClass().add("task-key");
        Label summary = new Label(task.summary().isEmpty() ? I18n.t("Task fora do Jira") : task.summary());
        summary.getStyleClass().add("task-summary");
        VBox text = new VBox(2, key, summary);
        if (!task.mine()) {
            Label owner = new Label(task.assignee().isEmpty() ? I18n.t("Sem responsável") : I18n.t("De {0}", task.assignee()));
            owner.getStyleClass().add("task-owner");
            text.getChildren().add(owner);
        }
        List<String> details = new java.util.ArrayList<>(boards);
        if (showUpdated) {
            task.updated().ifPresent(at -> details.add(I18n.t("modificada {0}", Formats.dateTime(at))));
        }
        if (!details.isEmpty()) {
            Label board = new Label(String.join(" · ", details));
            board.getStyleClass().add("task-board");
            text.getChildren().add(board);
        }
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label time = new Label(Formats.hoursMinutes(task.totalTime()));
        time.getStyleClass().add(task.running() ? "task-time-running" : "task-time");
        time.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(12, text, statusBadge(task), time, toggleButton(task, onToggle));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("task-row");
        if (task.running()) {
            row.getStyleClass().add("task-row-running");
        }
        if (!task.timeAllowed()) {
            row.getStyleClass().add("task-row-locked");
            Tooltip.install(row, new Tooltip(I18n.t("Fora das colunas monitoradas: não aceita tempo")));
        }
        return row;
    }

    public static Label statusBadge(TaskView task) {
        String text = task.statusName().isEmpty() ? I18n.t("Manual") : task.statusName();
        Label badge = new Label(text);
        badge.getStyleClass().addAll("badge", badgeClass(task.category()));
        badge.setMinWidth(Region.USE_PREF_SIZE);
        return badge;
    }

    public static String badgeClass(StatusCategory category) {
        return switch (category) {
            case IN_PROGRESS -> "badge-progress";
            case DONE -> "badge-done";
            case TO_DO -> "badge-todo";
        };
    }

    private static Button toggleButton(TaskView task, Consumer<TaskView> onToggle) {
        Button button = new Button();
        button.setGraphic(Icons.of(task.running() ? Icons.PAUSE : Icons.PLAY, 14));
        button.getStyleClass().add("row-toggle");
        if (task.running()) {
            button.getStyleClass().add("row-toggle-running");
        }
        button.setTooltip(new Tooltip(task.running() ? I18n.t("Pausar o tempo desta task") : I18n.t("Contar tempo nesta task")));
        if (!task.running() && !task.timeAllowed()) {
            button.setDisable(true);
            // Botão desativado não mostra tooltip: explica pela linha.
            button.setTooltip(null);
        }
        button.setOnAction(event -> onToggle.accept(task));
        return button;
    }
}
