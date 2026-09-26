package com.chronos.tracker.ui;

import com.chronos.tracker.activity.ActivityState;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dashboard: status de atividade, Jira, tempo do dia e a lista de tasks com play/pausa em cada uma.
 *
 * <p>O monitoramento e o Jira rodam em threads de fundo; a UI só é tocada via {@link Platform#runLater}.
 */
public final class DashboardController {

    private final TrackingEngine engine;
    private final Duration pollingInterval;

    private final Circle statusDot = new Circle(6);
    private final Label statusLabel = new Label();
    private final Label todayLabel = new Label();
    private final Label jiraLabel = new Label();
    private final VBox taskList = new VBox(6);
    private final TextField manualIssueField = new TextField();
    private final VBox root;

    private ScheduledExecutorService scheduler;

    public DashboardController(TrackingEngine engine, Duration pollingInterval) {
        this.engine = engine;
        this.pollingInterval = pollingInterval;
        this.root = buildView();
    }

    public Parent getView() {
        return root;
    }

    public void start() {
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "chronos-worker");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::safePollJira, 0, pollingInterval.toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::safeTick, 0, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        engine.shutdown();
    }

    private void safePollJira() {
        try {
            engine.pollJira();
        } catch (RuntimeException e) {
            // Uma falha inesperada não pode cancelar o agendamento.
            e.printStackTrace();
        }
    }

    private void safeTick() {
        try {
            Snapshot snapshot = engine.tick();
            Platform.runLater(() -> render(snapshot));
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private void render(Snapshot snapshot) {
        ActivityState activity = snapshot.activity();
        statusDot.getStyleClass().setAll("status-dot", "status-" + activity.name().toLowerCase().replace('_', '-'));
        statusLabel.setText(activity.label());
        todayLabel.setText(DurationFormat.hms(snapshot.activeToday()));
        jiraLabel.setText(snapshot.jiraStatus().label());

        taskList.getChildren().clear();
        if (snapshot.tasks().isEmpty()) {
            taskList.getChildren().add(caption("Nenhuma task sua no Jira"));
        }
        for (TaskView task : snapshot.tasks()) {
            taskList.getChildren().add(taskRow(task));
        }
    }

    private HBox taskRow(TaskView task) {
        Label key = new Label(task.key());
        key.getStyleClass().add("task-key");
        Label summary = new Label(task.summary());
        summary.getStyleClass().add("summary");
        Label status = new Label(task.statusName());
        status.getStyleClass().add("summary");
        VBox text = new VBox(2, key, summary, status);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        summary.setMaxWidth(230);

        Label time = new Label(DurationFormat.hms(task.totalTime()));
        time.getStyleClass().add(task.running() ? "task-time-running" : "task-time");

        Button toggle = new Button(task.running() ? "Pausar" : "Iniciar");
        toggle.setMinWidth(Region.USE_PREF_SIZE);
        toggle.setOnAction(event -> {
            if (task.running()) {
                engine.pause(task.key());
            } else {
                engine.play(task.key());
            }
            render(engine.tick());
        });

        HBox row = new HBox(10, text, time, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add(task.running() ? "task-row-running" : "task-row");
        return row;
    }

    private VBox buildView() {
        Label title = new Label("Work Tracker");
        title.getStyleClass().add("title");

        HBox status = new HBox(8, statusDot, statusLabel);
        status.setAlignment(Pos.CENTER_LEFT);
        statusDot.getStyleClass().add("status-dot");

        todayLabel.getStyleClass().add("time");

        GridPane grid = new GridPane();
        grid.getStyleClass().add("grid");
        grid.addRow(0, caption("Status"), status);
        grid.addRow(1, caption("Tempo hoje"), todayLabel);
        grid.addRow(2, caption("Jira"), jiraLabel);

        ScrollPane scroll = new ScrollPane(taskList);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("task-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        manualIssueField.setPromptText("Outra task, ex.: PROJ-123");
        HBox.setHgrow(manualIssueField, Priority.ALWAYS);
        Button startButton = new Button("Iniciar");
        startButton.setDefaultButton(true);
        startButton.setOnAction(event -> {
            engine.play(manualIssueField.getText());
            manualIssueField.clear();
        });
        HBox manual = new HBox(8, manualIssueField, startButton);
        manual.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(14, title, grid, caption("Tasks"), scroll, manual);
        box.getStyleClass().add("dashboard");

        render(engine.tick());
        return box;
    }

    private static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("caption");
        return label;
    }
}
