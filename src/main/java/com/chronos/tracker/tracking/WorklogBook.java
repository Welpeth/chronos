package com.chronos.tracker.tracking;

import com.chronos.tracker.jira.JiraException;
import com.chronos.tracker.jira.JiraService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Apontamentos: o tempo que o Chronos contou em cada task e quanto dele já foi lançado no "Controle de tempo"
 * do Jira. Quando a task sai da coluna e pausa, o que falta aparece para apontar com um clique.
 */
public final class WorklogBook {

    /** O Jira guarda horas em minutos: menos que isso não dá para apontar. */
    static final Duration MINIMUM = Duration.ofMinutes(1);

    public enum Status { COUNTING, PENDING, LOGGED }

    /**
     * Uma task da aba de apontamentos.
     *
     * @param pending tempo que ainda falta apontar, em minutos inteiros
     */
    public record Item(String key, String summary, Duration total, Duration logged, Duration pending,
                       Status status, Instant lastWorkedAt) {
    }

    private final HistoryStore store;
    private final Supplier<JiraService> jira;
    private final Clock clock;

    public WorklogBook(HistoryStore store, Supplier<JiraService> jira, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.jira = Objects.requireNonNull(jira, "jira");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Tasks com tempo gravado, primeiro as que faltam apontar, depois as que ainda estão contando e por
     * último as já apontadas; dentro de cada grupo, da trabalhada mais recentemente para a mais antiga.
     *
     * @param live tasks do momento, para saber quais estão contando e o tempo delas até agora
     */
    public List<Item> items(Collection<TaskView> live) throws HistoryStore.HistoryException {
        Map<String, TaskView> byKey = live.stream()
                .collect(Collectors.toMap(TaskView::key, Function.identity(), (a, b) -> a));
        List<Item> items = new ArrayList<>();
        for (HistoryStore.TaskTime time : store.taskTimes()) {
            TaskView view = byKey.get(time.issueKey());
            boolean running = view != null && view.running();
            Duration total = running && view.totalTime().compareTo(time.total()) > 0 ? view.totalTime() : time.total();
            String summary = view != null && !view.summary().isEmpty() ? view.summary() : time.summary();
            Duration pending = wholeMinutes(total.minus(time.logged()));
            Status status = running ? Status.COUNTING : pending.isZero() ? Status.LOGGED : Status.PENDING;
            Instant last = running ? clock.instant() : time.lastWorkedAt();
            items.add(new Item(time.issueKey(), summary, total, time.logged(), pending, status, last));
        }
        items.sort(Comparator.comparing((Item item) -> order(item.status()))
                .thenComparing(Item::lastWorkedAt, Comparator.reverseOrder()));
        return items;
    }

    /**
     * Lança no Jira o que falta apontar da task e grava que foi apontado. Devolve o tempo lançado.
     * Bloqueia: nunca chamar na thread da UI.
     */
    public synchronized Duration log(String issueKey) throws JiraException, HistoryStore.HistoryException {
        HistoryStore.TaskTime time = store.taskTimes().stream()
                .filter(t -> t.issueKey().equals(issueKey))
                .findFirst()
                .orElseThrow(() -> new JiraException("Nenhum tempo gravado em " + issueKey));
        Duration pending = wholeMinutes(time.total().minus(time.logged()));
        if (pending.isZero()) {
            throw new JiraException(issueKey + " já está apontada");
        }
        // O registro termina quando a task foi trabalhada pela última vez.
        Instant started = time.lastWorkedAt().minus(pending);
        String worklogId = jira.get().addWorklog(issueKey, pending, started);
        store.saveWorklog(issueKey, pending, clock.instant(), worklogId);
        return pending;
    }

    /**
     * Se o quadro aceita apontamento, olhando o campo "Controle de tempo" de uma das tasks. Vazio quando não
     * há task para olhar ou o Jira não deixa saber. Bloqueia: nunca chamar na thread da UI.
     */
    public Optional<Boolean> timeTrackingAvailable(Collection<TaskView> live) throws JiraException {
        JiraService service = jira.get();
        Optional<TaskView> sample = live.stream().filter(task -> !task.statusName().isEmpty()).findFirst();
        if (!service.isConfigured() || sample.isEmpty()) {
            return Optional.empty();
        }
        return service.hasTimeTracking(sample.get().key());
    }

    static Duration wholeMinutes(Duration duration) {
        if (duration.compareTo(MINIMUM) < 0) {
            return Duration.ZERO;
        }
        return Duration.ofMinutes(duration.toMinutes());
    }

    private static int order(Status status) {
        return switch (status) {
            case PENDING -> 0;
            case COUNTING -> 1;
            case LOGGED -> 2;
        };
    }
}
