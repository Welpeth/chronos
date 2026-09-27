package com.chronos.tracker;

import com.chronos.tracker.activity.ActivityClassifier;
import com.chronos.tracker.activity.AlwaysActiveMonitor;
import com.chronos.tracker.config.AppConfig;
import com.chronos.tracker.jira.RestJiraService;
import com.chronos.tracker.persistence.SqliteHistoryStore;
import com.chronos.tracker.system.WindowsStartup;
import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.tracking.MultiTaskTracker;
import com.chronos.tracker.tracking.TrackingEngine;
import com.chronos.tracker.ui.AppController;
import com.chronos.tracker.ui.AppIcons;
import com.chronos.tracker.ui.TrayIconController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.time.Clock;

public final class ChronosApp extends Application {

    private static final Path ENV_FILE = Path.of(".env");

    private AppController controller;
    private SqliteHistoryStore store;
    private TrayIconController tray;
    private boolean trayHintShown;

    @Override
    public void start(Stage stage) {
        AppConfig config;
        try {
            config = AppConfig.load(ENV_FILE);
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

        controller = new AppController(engine, config, history, ENV_FILE);

        Scene scene = new Scene(controller.getView(), 1320, 860);
        scene.getStylesheets().add(getClass().getResource("ui/app.css").toExternalForm());
        stage.setTitle("Chronos");
        AppIcons.applyTo(stage);
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.setScene(scene);

        tray = new TrayIconController(trayActions(stage));
        boolean inTray = tray.install();
        if (inTray) {
            // Fechar a janela só esconde: o Chronos continua contando na bandeja até "Sair".
            Platform.setImplicitExit(false);
            stage.setOnCloseRequest(event -> {
                event.consume();
                stage.hide();
                if (!trayHintShown) {
                    trayHintShown = true;
                    tray.notify("O Chronos continua rodando",
                            "Clique no ícone da bandeja para abrir, ou com o botão direito para pausar ou sair.");
                }
            });
            controller.setSnapshotListener(tray::update);
        }

        boolean background = getParameters().getRaw().contains(WindowsStartup.BACKGROUND_ARG);
        if (!background || !inTray) {
            stage.show();
        }
        if (background && !inTray) {
            // Aberto pelo Windows ao entrar, sem bandeja: fica minimizado.
            stage.setIconified(true);
        }

        controller.start();
    }

    private TrayIconController.Actions trayActions(Stage stage) {
        return new TrayIconController.Actions() {
            @Override
            public void open() {
                Platform.runLater(() -> {
                    stage.show();
                    stage.setIconified(false);
                    stage.toFront();
                });
            }

            @Override
            public void pause(String issueKey) {
                Platform.runLater(() -> controller.pauseTask(issueKey));
            }

            @Override
            public void finish(String issueKey) {
                controller.finishTask(issueKey, message -> tray.notify("Chronos", message));
            }

            @Override
            public void pauseAll() {
                Platform.runLater(controller::pauseAllTasks);
            }

            @Override
            public void exit() {
                Platform.runLater(Platform::exit);
            }
        };
    }

    @Override
    public void stop() {
        if (tray != null) {
            tray.remove();
        }
        if (controller != null) {
            controller.stop();
        }
        if (store != null) {
            store.close();
        }
        if (tray != null && tray.wasInstalled()) {
            // A thread do AWT (bandeja) seguraria o processo aberto.
            System.exit(0);
        }
    }
}
