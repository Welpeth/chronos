package com.chronos.tracker;

import com.chronos.tracker.activity.ActivityClassifier;
import com.chronos.tracker.activity.ActivityMonitors;
import com.chronos.tracker.config.AppConfig;
import com.chronos.tracker.config.AppPaths;
import com.chronos.tracker.config.EnvFile;
import com.chronos.tracker.config.I18n;
import com.chronos.tracker.config.TokenExpiry;
import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.RestJiraService;
import com.chronos.tracker.persistence.SqliteHistoryStore;
import com.chronos.tracker.system.SingleInstance;
import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.tracking.MultiTaskTracker;
import com.chronos.tracker.tracking.TrackingEngine;
import com.chronos.tracker.ui.AppController;
import com.chronos.tracker.ui.AppIcons;
import com.chronos.tracker.ui.Themes;
import com.chronos.tracker.ui.WindowSize;
import com.chronos.tracker.ui.TrayIconController;
import com.chronos.tracker.ui.TrayMenu;
import com.chronos.tracker.update.Backups;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            new Alert(Alert.AlertType.ERROR, I18n.t("Não foi possível ler o arquivo .env:\n{0}", e.getMessage()))
                    .showAndWait();
            return;
        }

        I18n.use(config.language());
        Clock clock = Clock.systemDefaultZone();
        Path database = AppPaths.resolve(config.databasePath());
        Optional<Path> restored = Optional.empty();
        try {
            restored = new Backups(AppPaths.dataDir()).applyPendingRestore(database, LocalDateTime.now(clock));
        } catch (IOException e) {
            new Alert(Alert.AlertType.WARNING, I18n.t(
                    "A restauração do histórico falhou:\n{0}\n\nO Chronos abre com o histórico que já estava em uso.",
                    e.getMessage())).showAndWait();
        }
        HistoryStore history;
        try {
            store = new SqliteHistoryStore(database, clock.getZone(), config.jiraSite());
            history = store;
        } catch (HistoryStore.HistoryException e) {
            new Alert(Alert.AlertType.WARNING,
                    I18n.t("{0}\n\nO app vai funcionar, mas o tempo não será gravado.", e.getMessage())).showAndWait();
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

        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        WindowSize size = WindowSize.fit(screen.getWidth(), screen.getHeight());
        Scene scene = new Scene(controller.getView(), size.width(), size.height());
        Themes.apply(scene, config.darkMode());
        stage.setTitle("Chronos");
        AppIcons.applyTo(stage);
        stage.setMinWidth(size.minWidth());
        stage.setMinHeight(size.minHeight());
        stage.setScene(scene);
        stage.setMaximized(size.maximized());

        tray = new TrayIconController(trayActions(stage));
        TrayMenu trayMenu = new TrayMenu(trayActions(stage), () -> Themes.isDark(scene));
        tray.setMenuPresenter(trayMenu::show);
        boolean inTray = tray.install();
        if (inTray) {
            // Fechar a janela só esconde: o Chronos continua contando na bandeja até "Sair".
            Platform.setImplicitExit(false);
            stage.setOnCloseRequest(event -> {
                event.consume();
                stage.hide();
                if (!trayHintShown) {
                    trayHintShown = true;
                    tray.notify(I18n.t("O Chronos continua rodando"),
                            I18n.t("Clique no ícone da bandeja para abrir, ou com o botão direito para pausar ou sair."));
                }
            });
            controller.setSnapshotListener(snapshot -> {
                tray.update(snapshot);
                trayMenu.update(snapshot);
            });
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

        // Também quando o Windows abre o Chronos ao entrar: a janela aparece (antes ia direto para a bandeja).
        stage.show();
        if (!size.maximized()) {
            stage.centerOnScreen();
        }

        controller.start();
        warnAboutTokenExpiry();
        restored.ifPresent(backup -> {
            Alert done = new Alert(Alert.AlertType.INFORMATION, I18n.t(
                    "O histórico agora é a cópia {0}. O que estava em uso antes ficou guardado na pasta backup.",
                    backup.getFileName()));
            done.setHeaderText(I18n.t("Base histórica restaurada"));
            done.initOwner(stage);
            done.show();
        });
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
                tray.alert(I18n.t("API token do Jira"), I18n.t(
                        "{0}. Gere um novo em id.atlassian.com e troque em Configurações.",
                        TokenExpiry.describe(expires, today)));
            }
        });
    }

    private void showAlerts(Stage stage, List<JiraIssue> issues) {
        for (JiraIssue issue : issues) {
            String type = issue.issueType().isEmpty() ? I18n.t("Nova task") : issue.issueType();
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
