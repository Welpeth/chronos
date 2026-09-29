package com.chronos.tracker;

import com.chronos.tracker.activity.ActivityClassifier;
import com.chronos.tracker.activity.ActivityMonitors;
import com.chronos.tracker.config.AppConfig;
import com.chronos.tracker.config.AppPaths;
import com.chronos.tracker.config.EnvFile;
import com.chronos.tracker.config.TokenExpiry;
import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.RestJiraService;
import com.chronos.tracker.persistence.SqliteHistoryStore;
import com.chronos.tracker.system.SingleInstance;
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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ChronosApp extends Application {

    private static final Path ENV_FILE = AppPaths.envFile();
    /** Garante um Chronos só; o {@link Launcher} preenche antes de abrir o app. */
    static SingleInstance singleInstance;

    private AppController controller;
    private SqliteHistoryStore store;
    private TrayIconController tray;
    private boolean trayHintShown;
    private boolean badge;

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
            store = new SqliteHistoryStore(AppPaths.resolve(config.databasePath()), clock.getZone(), config.jiraSite());
            history = store;
        } catch (HistoryStore.HistoryException e) {
            new Alert(Alert.AlertType.WARNING, e.getMessage()
                    + "\n\nO app vai funcionar, mas o tempo não será gravado.").showAndWait();
            history = HistoryStore.NONE;
        }

        TrackingEngine engine = new TrackingEngine(
                new MultiTaskTracker(clock),
                ActivityMonitors.forThisSystem(),
                new ActivityClassifier(config.possiblyIdleAfter(), config.inactiveAfter()),
                RestJiraService.from(config),
                clock,
                history);
        engine.setWorkingStatuses(config.workingStatuses());
        engine.setAutoStart(config.autoStart());
        engine.setOnlyWorkingColumns(config.onlyWorkingColumns());
        engine.setValidationLabels(config.playLabels(), config.doneLabels());

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
        controller.setAlertListener(issues -> showAlerts(stage, issues));
        // Abriu a janela: os avisos foram vistos.
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                setBadge(stage, false);
            }
        });

        if (singleInstance != null) {
            // Abriram o Chronos de novo com ele na bandeja: mostra esta janela.
            TrayIconController.Actions actions = trayActions(stage);
            singleInstance.onShowRequested(actions::open);
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
        warnAboutTokenExpiry();
    }

    /** Ao abrir: avisa se o API token do Jira vence em até duas semanas ou já venceu. */
    private void warnAboutTokenExpiry() {
        Map<String, String> env;
        try {
            env = EnvFile.read(ENV_FILE);
        } catch (IOException e) {
            return;
        }
        TokenExpiry.from(env).ifPresent(expires -> {
            LocalDate today = LocalDate.now();
            if (TokenExpiry.level(expires, today) != TokenExpiry.Level.OK && tray.wasInstalled()) {
                tray.alert("API token do Jira", TokenExpiry.describe(expires, today)
                        + ". Gere um novo em id.atlassian.com e troque em Configurações.");
            }
        });
    }

    private void showAlerts(Stage stage, List<JiraIssue> issues) {
        for (JiraIssue issue : issues) {
            String type = issue.issueType().isEmpty() ? "Nova task" : issue.issueType();
            String message = issue.summary().isEmpty() ? issue.key() : issue.key() + " · " + issue.summary();
            if (tray.wasInstalled()) {
                tray.alert(type, message);
            }
        }
        if (!stage.isShowing() || !stage.isFocused()) {
            setBadge(stage, true);
        }
    }

    private void setBadge(Stage stage, boolean on) {
        if (badge == on) {
            return;
        }
        badge = on;
        AppIcons.applyTo(stage, on);
        tray.setBadge(on);
    }

    private TrayIconController.Actions trayActions(Stage stage) {
        return new TrayIconController.Actions() {
            @Override
            public void open() {
                Platform.runLater(() -> {
                    stage.show();
                    stage.setIconified(false);
                    stage.toFront();
                    setBadge(stage, false);
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
        if (singleInstance != null) {
            singleInstance.close();
        }
        if (tray != null && tray.wasInstalled()) {
            // A thread do AWT (bandeja) seguraria o processo aberto.
            System.exit(0);
        }
    }
}
