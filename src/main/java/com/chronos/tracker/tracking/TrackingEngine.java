package com.chronos.tracker.tracking;

import com.chronos.tracker.activity.ActivityClassifier;
import com.chronos.tracker.activity.ActivityMonitor;
import com.chronos.tracker.activity.ActivityState;
import com.chronos.tracker.jira.JiraException;
import com.chronos.tracker.jira.JiraService;
import com.chronos.tracker.jira.JiraSyncStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Junta atividade, Jira e o cronômetro. Não depende de JavaFX, então pode rodar em qualquer thread.
 *
 * <p>{@link #tick()} roda a cada segundo e é barato; {@link #pollJira()} faz a chamada de rede e roda
 * no intervalo de polling configurado. A issue manual, quando definida, tem prioridade sobre o Jira.
 */
public final class TrackingEngine {

    private final TimeTracker tracker;
    private final ActivityMonitor activityMonitor;
    private final ActivityClassifier classifier;
    private final JiraService jiraService;

    private volatile Optional<String> jiraIssue = Optional.empty();
    private volatile Optional<String> manualIssue = Optional.empty();
    private volatile JiraSyncStatus jiraStatus;

    public TrackingEngine(TimeTracker tracker, ActivityMonitor activityMonitor,
                          ActivityClassifier classifier, JiraService jiraService) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.activityMonitor = Objects.requireNonNull(activityMonitor, "activityMonitor");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.jiraService = Objects.requireNonNull(jiraService, "jiraService");
        this.jiraStatus = jiraService.isConfigured() ? JiraSyncStatus.SYNCING : JiraSyncStatus.NOT_CONFIGURED;
    }

    /** Aplica um ciclo de monitoramento e devolve o estado atual para a UI. */
    public Snapshot tick() {
        ActivityState activity = classifier.classify(activityMonitor.getIdleTime());
        Optional<String> issue = manualIssue.or(() -> jiraIssue);
        tracker.update(issue, activity);
        Optional<String> current = tracker.getCurrentIssue();
        return new Snapshot(
                activity,
                current,
                tracker.getElapsedTime(),
                current.map(tracker::totalFor).orElse(Duration.ZERO),
                tracker.getState(),
                jiraStatus);
    }

    /** Consulta o Jira. Bloqueia durante a chamada de rede; nunca chamar na thread da UI. */
    public void pollJira() {
        if (!jiraService.isConfigured()) {
            jiraStatus = JiraSyncStatus.NOT_CONFIGURED;
            return;
        }
        try {
            jiraIssue = jiraService.fetchCurrentIssueKey();
            jiraStatus = JiraSyncStatus.SYNCED;
        } catch (JiraException e) {
            // Mantém a última issue conhecida: uma queda curta de rede não deve interromper o tracking.
            jiraStatus = JiraSyncStatus.ERROR;
        }
    }

    public void setManualIssue(String issueKey) {
        String normalized = issueKey == null ? "" : issueKey.strip().toUpperCase();
        manualIssue = normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }

    public void clearManualIssue() {
        manualIssue = Optional.empty();
    }

    /** Encerra a issue atual, por exemplo ao fechar o aplicativo. */
    public Optional<TimeEntry> shutdown() {
        return tracker.stop();
    }

    public record Snapshot(
            ActivityState activity,
            Optional<String> issueKey,
            Duration elapsed,
            Duration totalForIssue,
            TrackerState trackerState,
            JiraSyncStatus jiraStatus) {
    }
}
