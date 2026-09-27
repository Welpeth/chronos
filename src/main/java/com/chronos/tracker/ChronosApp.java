package com.chronos.tracker;

import com.chronos.tracker.activity.ActivityClassifier;
import com.chronos.tracker.activity.AlwaysActiveMonitor;
import com.chronos.tracker.config.AppConfig;
import com.chronos.tracker.jira.RestJiraService;
import com.chronos.tracker.persistence.SqliteHistoryStore;
import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.tracking.MultiTaskTracker;
import com.chronos.tracker.tracking.TrackingEngine;
import com.chronos.tracker.ui.AppController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.time.Clock;

public final class ChronosApp extends Application {

    private AppController controller;
    private SqliteHistoryStore store;

    @Override
    public void start(Stage stage) {
        AppConfig config;
        try {
            config = AppConfig.load(Path.of(".env"));
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Não foi possível ler o arquivo .env:\n" + e.getMessage()).showAndWait();
            return;
        }

        Clock clock = Clock.systemDefaultZone();
        HistoryStore history;
        try {
            store = new SqliteHistoryStore(config.databasePath(), clock.getZone());
            history = store;
        } catch (HistoryStore.HistoryException e) {
            new Alert(Alert.AlertType.WARNING, e.getMessage()
                    + "\n\nO app vai funcionar, mas o tempo não será gravado.").showAndWait();
            history = HistoryStore.NONE;
        }

        TrackingEngine engine = new TrackingEngine(
                new MultiTaskTracker(clock),
                new AlwaysActiveMonitor(),
                new ActivityClassifier(config.possiblyIdleAfter(), config.inactiveAfter()),
                RestJiraService.from(config),
                clock,
                history);
        engine.setWorkingStatuses(config.workingStatuses());

        controller = new AppController(engine, config.pollingInterval(), history);

        Scene scene = new Scene(controller.getView(), 1320, 860);
        scene.getStylesheets().add(getClass().getResource("ui/app.css").toExternalForm());
        stage.setTitle("Chronos");
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.setScene(scene);
        stage.show();

        controller.start();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.stop();
        }
        if (store != null) {
            store.close();
        }
    }
}
