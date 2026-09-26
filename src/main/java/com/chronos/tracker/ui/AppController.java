package com.chronos.tracker.ui;

import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.application.Platform;
import javafx.scene.Parent;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Liga o motor de tracking à janela. O monitoramento e o Jira rodam em threads de fundo;
 * a UI só é tocada via {@link Platform#runLater}.
 */
public final class AppController {

    private final TrackingEngine engine;
    private final Duration pollingInterval;
    private final MainWindow window;

    private ScheduledExecutorService scheduler;

    public AppController(TrackingEngine engine, Duration pollingInterval, HistoryStore store) {
        this.engine = engine;
        this.pollingInterval = pollingInterval;
        this.window = new MainWindow(this::toggle, store);
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
        scheduler.scheduleWithFixedDelay(this::safePollJira, 0, pollingInterval.toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::safeTick, 0, 1, TimeUnit.SECONDS);
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
