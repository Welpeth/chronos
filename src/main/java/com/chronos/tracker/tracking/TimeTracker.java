package com.chronos.tracker.tracking;

import com.chronos.tracker.activity.ActivityState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Cronômetro por issue do Jira.
 *
 * <p>Os métodos são sincronizados porque o tracker é atualizado pela thread de polling e lido pela UI.
 */
public final class TimeTracker {

    private final Clock clock;
    private final List<TimeEntry> completedEntries = new ArrayList<>();
    private final Map<String, Duration> completedTotals = new HashMap<>();

    private TrackerState state = TrackerState.STOPPED;
    private String currentIssue;
    private Instant startedAt;
    private Instant runningSince;
    private Duration accumulated = Duration.ZERO;

    public TimeTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Começa a contar tempo para {@code issueKey}, encerrando antes a issue atual se houver uma.
     *
     * @return a entrada da issue anterior, se ela foi encerrada
     */
    public synchronized Optional<TimeEntry> start(String issueKey) {
        Objects.requireNonNull(issueKey, "issueKey");
        Optional<TimeEntry> previous = stop();
        Instant now = clock.instant();
        currentIssue = issueKey;
        startedAt = now;
        runningSince = now;
        accumulated = Duration.ZERO;
        state = TrackerState.RUNNING;
        return previous;
    }

    public synchronized void pause() {
        if (state != TrackerState.RUNNING) {
            return;
        }
        accumulated = accumulated.plus(Duration.between(runningSince, clock.instant()));
        runningSince = null;
        state = TrackerState.PAUSED;
    }

    public synchronized void resume() {
        if (state != TrackerState.PAUSED) {
            return;
        }
        runningSince = clock.instant();
        state = TrackerState.RUNNING;
    }

    /**
     * Encerra a issue atual.
     *
     * @return a entrada encerrada, ou vazio se nada estava sendo rastreado
     */
    public synchronized Optional<TimeEntry> stop() {
        if (state == TrackerState.STOPPED) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        TimeEntry entry = new TimeEntry(currentIssue, startedAt, now, getElapsedTime());
        completedEntries.add(entry);
        completedTotals.merge(currentIssue, entry.activeTime(), Duration::plus);

        state = TrackerState.STOPPED;
        currentIssue = null;
        startedAt = null;
        runningSince = null;
        accumulated = Duration.ZERO;
        return Optional.of(entry);
    }

    /**
     * Aplica um ciclo de monitoramento: troca de issue quando ela muda, pausa quando o usuário
     * fica inativo e retoma quando ele volta.
     *
     * @param issueKey issue em que o usuário está trabalhando agora, ou vazio se nenhuma
     * @param activity estado de atividade atual
     * @return a entrada encerrada neste ciclo, se houve troca ou fim de issue
     */
    public synchronized Optional<TimeEntry> update(Optional<String> issueKey, ActivityState activity) {
        Optional<TimeEntry> closed = Optional.empty();
        if (issueKey.isEmpty()) {
            return stop();
        }
        if (!issueKey.get().equals(currentIssue)) {
            closed = start(issueKey.get());
        }
        if (activity == ActivityState.INACTIVE) {
            pause();
        } else {
            resume();
        }
        return closed;
    }

    /** Tempo ativo da issue atual. */
    public synchronized Duration getElapsedTime() {
        if (state == TrackerState.RUNNING) {
            return accumulated.plus(Duration.between(runningSince, clock.instant()));
        }
        return accumulated;
    }

    /** Tempo ativo total da issue, somando entradas encerradas e a sessão atual. */
    public synchronized Duration totalFor(String issueKey) {
        Duration total = completedTotals.getOrDefault(issueKey, Duration.ZERO);
        if (issueKey.equals(currentIssue)) {
            total = total.plus(getElapsedTime());
        }
        return total;
    }

    public synchronized TrackerState getState() {
        return state;
    }

    public synchronized Optional<String> getCurrentIssue() {
        return Optional.ofNullable(currentIssue);
    }

    public synchronized List<TimeEntry> getCompletedEntries() {
        return List.copyOf(completedEntries);
    }
}
