package com.chronos.tracker.ui;

import com.chronos.tracker.activity.ActivityState;
import com.chronos.tracker.tracking.TrackingEngine;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dashboard da Fase 1: status de atividade, issue atual, tempo e estado da sincronização com o Jira.
 *
 * <p>O monitoramento e o Jira rodam em threads de fundo; a UI só é tocada via {@link Platform#runLater}.
 */
public final class DashboardController {

    private final TrackingEngine engine;
    private final Duration pollingInterval;

    private final Circle statusDot = new Circle(6);
    private final Label statusLabel = new Label();
    private final Label taskLabel = new Label();
    private final Label summaryLabel = new Label();
    private final Label timeLabel = new Label();
    private final Label totalLabel = new Label();
    private final Label jiraLabel = new Label();
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
        taskLabel.setText(snapshot.issueKey().orElse("—"));
        summaryLabel.setText(snapshot.issueSummary().orElse(""));
        timeLabel.setText(DurationFormat.hms(snapshot.elapsed()));
        totalLabel.setText(DurationFormat.hms(snapshot.totalForIssue()));
        jiraLabel.setText(snapshot.jiraStatus().label());
    }

    private VBox buildView() {
        Label title = new Label("Work Tracker");
        title.getStyleClass().add("title");

        HBox status = new HBox(8, statusDot, statusLabel);
        status.setAlignment(Pos.CENTER_LEFT);
        statusDot.getStyleClass().add("status-dot");

        timeLabel.getStyleClass().add("time");

        GridPane grid = new GridPane();
        grid.getStyleClass().add("grid");
        grid.addRow(0, caption("Status"), status);
        summaryLabel.getStyleClass().add("summary");
        summaryLabel.setWrapText(true);
        summaryLabel.setMaxWidth(220);
        grid.addRow(1, caption("Task"), new VBox(2, taskLabel, summaryLabel));
        grid.addRow(2, caption("Tempo"), timeLabel);
        grid.addRow(3, caption("Total na task"), totalLabel);
        grid.addRow(4, caption("Jira"), jiraLabel);

        manualIssueField.setPromptText("Ex.: PROJ-123");
        HBox.setHgrow(manualIssueField, Priority.ALWAYS);
        Button startButton = new Button("Iniciar");
        startButton.setDefaultButton(true);
        startButton.setOnAction(event -> engine.setManualIssue(manualIssueField.getText()));
        Button stopButton = new Button("Parar");
        stopButton.setOnAction(event -> {
            engine.clearManualIssue();
            manualIssueField.clear();
        });
        HBox manual = new HBox(8, manualIssueField, startButton, stopButton);
        manual.setAlignment(Pos.CENTER_LEFT);

        Label manualCaption = caption("Task manual (tem prioridade sobre o Jira)");

        VBox box = new VBox(14, title, grid, manualCaption, manual);
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
