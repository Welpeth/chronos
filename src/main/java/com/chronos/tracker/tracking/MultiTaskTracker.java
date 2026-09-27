package com.chronos.tracker.tracking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Um cronômetro por issue do Jira. Vários podem contar ao mesmo tempo, e cada um recebe o tempo
 * inteiro: uma hora trabalhada com duas tasks ligadas soma uma hora em cada uma.
 *
 * <p>Cada intervalo em que uma task contou tempo vira um {@link TimeEntry}.
 * Os métodos são sincronizados porque o tracker é atualizado pela thread de monitoramento e lido pela UI.
 */
public final class MultiTaskTracker {

    private final Clock clock;
    private final Map<String, Duration> completedTotals = new HashMap<>();
    private final Map<String, Instant> runningSince = new HashMap<>();
    private final List<TimeEntry> completedEntries = new ArrayList<>();

    public MultiTaskTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Soma tempo já contado antes (por exemplo, lido do histórico ao abrir o app). */
    public synchronized void preload(Map<String, Duration> totals) {
        totals.forEach((key, total) -> completedTotals.merge(key, total, Duration::plus));
    }

    /** Esquece todo o tempo contado (por exemplo, ao trocar de Jira). Chamar só com nada contando. */
    public synchronized void reset() {
        completedTotals.clear();
        runningSince.clear();
        completedEntries.clear();
    }

    /** Começa a contar tempo em {@code issueKey}. Não faz nada se já estiver contando. */
    public synchronized void start(String issueKey) {
        Objects.requireNonNull(issueKey, "issueKey");
        runningSince.putIfAbsent(issueKey, clock.instant());
    }

    /**
     * Para de contar tempo em {@code issueKey}.
     *
     * @return o intervalo encerrado, ou vazio se a task não estava contando
     */
    public synchronized Optional<TimeEntry> pause(String issueKey) {
        Instant since = runningSince.remove(issueKey);
        if (since == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        TimeEntry entry = new TimeEntry(issueKey, since, now, Duration.between(since, now));
        completedEntries.add(entry);
        completedTotals.merge(issueKey, entry.activeTime(), Duration::plus);
        return Optional.of(entry);
    }

    /** Para todas as tasks, por exemplo ao fechar o aplicativo. */
    public synchronized List<TimeEntry> pauseAll() {
        List<TimeEntry> closed = new ArrayList<>();
        for (String key : List.copyOf(runningSince.keySet())) {
            pause(key).ifPresent(closed::add);
        }
        return closed;
    }

    public synchronized boolean isRunning(String issueKey) {
        return runningSince.containsKey(issueKey);
    }

    public synchronized Set<String> runningKeys() {
        return Set.copyOf(runningSince.keySet());
    }

    /** Desde quando a task está contando no intervalo atual. */
    public synchronized Optional<Instant> runningSince(String issueKey) {
        return Optional.ofNullable(runningSince.get(issueKey));
    }

    /** Tempo total da task: intervalos encerrados mais o intervalo atual. */
    public synchronized Duration totalFor(String issueKey) {
        Duration total = completedTotals.getOrDefault(issueKey, Duration.ZERO);
        Instant since = runningSince.get(issueKey);
        if (since != null) {
            total = total.plus(Duration.between(since, clock.instant()));
        }
        return total;
    }

    /** Tasks que já contaram tempo alguma vez, inclusive as que não vieram do Jira. */
    public synchronized Set<String> trackedKeys() {
        Set<String> keys = new HashSet<>(completedTotals.keySet());
        keys.addAll(runningSince.keySet());
        return keys;
    }

    public synchronized List<TimeEntry> getCompletedEntries() {
        return List.copyOf(completedEntries);
    }
}
