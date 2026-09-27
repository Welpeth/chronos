package com.chronos.tracker.ui;

import com.chronos.tracker.config.AppConfig;
import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.application.Platform;
import javafx.scene.Parent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Liga o motor de tracking à janela. O monitoramento e o Jira rodam em threads de fundo;
 * a UI só é tocada via {@link Platform#runLater}.
 */
public final class AppController {

    private final TrackingEngine engine;
    private final MainWindow window;
    private Duration pollingInterval;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> polling;

    public AppController(TrackingEngine engine, AppConfig config, HistoryStore store, Path envFile) {
        this.engine = engine;
        this.pollingInterval = config.pollingInterval();
        SettingsController settings = new SettingsController(envFile, config, engine, this::setPollingInterval);
        this.window = new MainWindow(this::toggle, this::addManual, store, settings);
        window.render(engine.tick());
    }

    public Parent getView() {
        return window.getView();
    }

    public void start() {
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "chronos-worker");
            thread.setDaemon(true);
            return thread;
        });
        schedulePolling();
        scheduler.scheduleAtFixedRate(this::safeTick, 0, 1, TimeUnit.SECONDS);
    }

    /** Reagenda o Jira com o novo intervalo e consulta na hora (as configurações acabaram de mudar). */
    private synchronized void setPollingInterval(Duration interval) {
        pollingInterval = interval;
        if (scheduler != null) {
            schedulePolling();
        }
    }

    private synchronized void schedulePolling() {
        if (polling != null) {
            polling.cancel(false);
        }
        polling = scheduler.scheduleWithFixedDelay(this::safePollJira, 0, pollingInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        engine.shutdown();
    }

    private void toggle(TaskView task) {
        if (task.running()) {
            engine.pause(task.key());
        } else {
            engine.play(task.key());
        }
        window.render(engine.tick());
    }

    private void addManual() {
        Snapshot snapshot = engine.tick();
        String featured = snapshot.featuredTask().map(TaskView::key).orElse("");
        ManualEntryDialog.show(window.getView().getScene().getWindow(), snapshot.tasks(), featured,
                (key, date, duration, note) -> engine.addManual(key, date, duration, note));
        window.render(engine.tick());
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
            Platform.runLater(() -> window.render(snapshot));
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }
}
