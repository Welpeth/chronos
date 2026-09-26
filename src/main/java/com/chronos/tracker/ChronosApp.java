package com.chronos.tracker;

import com.chronos.tracker.activity.ActivityClassifier;
import com.chronos.tracker.activity.AlwaysActiveMonitor;
import com.chronos.tracker.config.AppConfig;
import com.chronos.tracker.jira.UnconfiguredJiraService;
import com.chronos.tracker.tracking.TimeTracker;
import com.chronos.tracker.tracking.TrackingEngine;
import com.chronos.tracker.ui.DashboardController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.time.Clock;

public final class ChronosApp extends Application {

    private DashboardController dashboard;

    @Override
    public void start(Stage stage) {
        AppConfig config;
        try {
            config = AppConfig.load(Path.of(".env"));
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Não foi possível ler o arquivo .env:\n" + e.getMessage()).showAndWait();
            return;
        }

        TrackingEngine engine = new TrackingEngine(
                new TimeTracker(Clock.systemUTC()),
                new AlwaysActiveMonitor(),
                new ActivityClassifier(config.possiblyIdleAfter(), config.inactiveAfter()),
                new UnconfiguredJiraService());

        dashboard = new DashboardController(engine, config.pollingInterval());

        Scene scene = new Scene(dashboard.getView(), 360, 340);
        scene.getStylesheets().add(getClass().getResource("ui/dashboard.css").toExternalForm());
        stage.setTitle("Chronos — Work Tracker");
        stage.setScene(scene);
        stage.show();

        dashboard.start();
    }

    @Override
    public void stop() {
        if (dashboard != null) {
            dashboard.stop();
        }
    }
}
