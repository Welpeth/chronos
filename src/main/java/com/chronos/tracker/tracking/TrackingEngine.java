package com.chronos.tracker.tracking;

import com.chronos.tracker.activity.ActivityClassifier;
import com.chronos.tracker.activity.ActivityMonitor;
import com.chronos.tracker.activity.ActivityState;
import com.chronos.tracker.jira.JiraAuthException;
import com.chronos.tracker.jira.JiraException;
import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.JiraQueryException;
import com.chronos.tracker.jira.JiraService;
import com.chronos.tracker.jira.JiraSyncStatus;
import com.chronos.tracker.jira.JiraUser;
import com.chronos.tracker.jira.StatusCategory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Junta atividade, Jira e os cronômetros. Não depende de JavaFX, então pode rodar em qualquer thread.
 *
 * <p>Por padrão, toda issue "em andamento" no Jira conta tempo, e várias contam ao mesmo tempo.
 * O play/pause do usuário numa task vale até o status dela mudar no Jira; aí o app volta a seguir o Jira.
 * Quando o usuário fica inativo, todas as tasks pausam e retomam juntas quando ele volta.
 *
 * <p>{@link #tick()} roda a cada segundo e é barato; {@link #pollJira()} faz as chamadas de rede e roda
 * no intervalo de polling configurado.
 */
public final class TrackingEngine {

    static final int MAX_EVENTS = 50;

    private final MultiTaskTracker tracker;
    private final ActivityMonitor activityMonitor;
    private final ActivityClassifier classifier;
    private final JiraService jiraService;
    private final Clock clock;
    private final ZoneId zone;

    // Escritos pela thread de polling, lidos no tick.
    private volatile List<JiraIssue> issues = List.of();
    private volatile long issuesVersion;
    private volatile JiraSyncStatus jiraStatus;
    private volatile Optional<String> jiraError = Optional.empty();
    private volatile Optional<Instant> lastSync = Optional.empty();
    private volatile Optional<JiraUser> user = Optional.empty();
    private volatile Optional<String> projectLabel = Optional.empty();

    // Protegidos por "this".
    private final Map<String, Boolean> overrides = new HashMap<>();
    private final Map<String, Boolean> lastInProgress = new HashMap<>();
    private long seenIssuesVersion;
    private boolean inactivityPause;
    private Instant inactiveSince;
    private boolean justResumed;
    private LocalDate day;
    private Instant lastTick;
    private Duration activeToday = Duration.ZERO;
    private Duration inactiveToday = Duration.ZERO;

    private final Deque<ActivityEvent> events = new ArrayDeque<>();

    public TrackingEngine(MultiTaskTracker tracker, ActivityMonitor activityMonitor, ActivityClassifier classifier,
                          JiraService jiraService, Clock clock) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.activityMonitor = Objects.requireNonNull(activityMonitor, "activityMonitor");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.jiraService = Objects.requireNonNull(jiraService, "jiraService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = clock.getZone();
        this.jiraStatus = jiraService.isConfigured() ? JiraSyncStatus.SYNCING : JiraSyncStatus.NOT_CONFIGURED;
    }

    /** Aplica um ciclo de monitoramento e devolve o estado atual para a UI. */
    public synchronized Snapshot tick() {
        Instant now = clock.instant();
        accountDailyTime(now);

        Duration idle = activityMonitor.getIdleTime();
        ActivityState activity = classifier.classify(idle);
        List<JiraIssue> currentIssues = issues;

        dropOverridesWhenJiraChanges(currentIssues);
        Set<String> wanted = wantedRunning(currentIssues);
        Set<String> effective = applyInactivity(activity, wanted, now);
        applyRunning(effective);

        return snapshot(activity, idle, currentIssues);
    }

    /** Consulta o Jira. Bloqueia durante as chamadas de rede; nunca chamar na thread da UI. */
    public void pollJira() {
        if (!jiraService.isConfigured()) {
            jiraStatus = JiraSyncStatus.NOT_CONFIGURED;
            return;
        }
        JiraSyncStatus previous = jiraStatus;
        try {
            List<JiraIssue> fetched = jiraService.fetchMyIssues();
            if (user.isEmpty()) {
                user = jiraService.fetchCurrentUser();
            }
            if (projectLabel.isEmpty()) {
                projectLabel = jiraService.fetchProjectLabel();
            }
            boolean changed = !fetched.equals(issues);
            issues = List.copyOf(fetched);
            issuesVersion++;
            jiraStatus = JiraSyncStatus.SYNCED;
            jiraError = Optional.empty();
            lastSync = Optional.of(clock.instant());
            if (changed || previous != JiraSyncStatus.SYNCED) {
                addEvent(ActivityEvent.Kind.SYNC, "Sincronização com Jira concluída", describeSync(fetched));
            }
        } catch (JiraException e) {
            // Mantém as últimas issues conhecidas: uma queda curta de rede não deve interromper o tracking.
            jiraStatus = e instanceof JiraAuthException ? JiraSyncStatus.AUTH_ERROR
                    : e instanceof JiraQueryException ? JiraSyncStatus.QUERY_ERROR
                    : JiraSyncStatus.ERROR;
            jiraError = Optional.ofNullable(e.getMessage());
            if (previous != jiraStatus) {
                addEvent(ActivityEvent.Kind.ERROR, "Falha ao sincronizar com o Jira", jiraError.orElse(""));
            }
        }
    }

    /** Liga o tempo de {@code issueKey} na hora, sem mexer nas outras tasks. */
    public synchronized void play(String issueKey) {
        String key = normalize(issueKey);
        if (key.isEmpty()) {
            return;
        }
        overrides.put(key, true);
        if (!inactivityPause && !tracker.isRunning(key)) {
            tracker.start(key);
            addEvent(ActivityEvent.Kind.TASK, "Tempo iniciado", "Task: " + key);
        }
    }

    /** Pausa o tempo de {@code issueKey} na hora, sem mexer nas outras tasks. */
    public synchronized void pause(String issueKey) {
        String key = normalize(issueKey);
        if (key.isEmpty()) {
            return;
        }
        overrides.put(key, false);
        if (tracker.pause(key).isPresent()) {
            addEvent(ActivityEvent.Kind.TASK, "Tempo pausado", "Task: " + key);
        }
    }

    /** Encerra todos os intervalos abertos, por exemplo ao fechar o aplicativo. */
    public synchronized List<TimeEntry> shutdown() {
        return tracker.pauseAll();
    }

    public List<TimeEntry> completedEntries() {
        return tracker.getCompletedEntries();
    }

    /** Tempo ativo do dia conta o relógio, não a soma das tasks: 1h com 3 tasks ligadas é 1h. */
    private void accountDailyTime(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (!today.equals(day)) {
            day = today;
            activeToday = Duration.ZERO;
            inactiveToday = Duration.ZERO;
            lastTick = now;
            return;
        }
        Duration delta = Duration.between(lastTick, now);
        lastTick = now;
        if (delta.isNegative()) {
            return;
        }
        if (!tracker.runningKeys().isEmpty()) {
            activeToday = activeToday.plus(delta);
        } else if (inactivityPause) {
            inactiveToday = inactiveToday.plus(delta);
        }
    }

    /** Quando uma issue entra ou sai de "em andamento" no Jira, o play/pause manual dela deixa de valer. */
    private void dropOverridesWhenJiraChanges(List<JiraIssue> currentIssues) {
        long version = issuesVersion;
        if (version == seenIssuesVersion) {
            return;
        }
        seenIssuesVersion = version;
        for (JiraIssue issue : currentIssues) {
            Boolean before = lastInProgress.put(issue.key(), issue.isInProgress());
            if (before != null && before != issue.isInProgress()) {
                overrides.remove(issue.key());
            }
        }
    }

    private Set<String> wantedRunning(List<JiraIssue> currentIssues) {
        Set<String> wanted = new LinkedHashSet<>();
        for (JiraIssue issue : currentIssues) {
            if (overrides.getOrDefault(issue.key(), issue.isInProgress())) {
                wanted.add(issue.key());
            }
        }
        overrides.forEach((key, on) -> {
            if (on) {
                wanted.add(key);
            }
        });
        return wanted;
    }

    private Set<String> applyInactivity(ActivityState activity, Set<String> wanted, Instant now) {
        if (wanted.isEmpty()) {
            inactivityPause = false;
            return wanted;
        }
        if (activity == ActivityState.INACTIVE) {
            if (!inactivityPause) {
                inactivityPause = true;
                inactiveSince = now;
                addEvent(ActivityEvent.Kind.ACTIVITY, "Inatividade detectada", "Tempo pausado em todas as tasks");
            }
            return Set.of();
        }
        if (inactivityPause) {
            inactivityPause = false;
            justResumed = true;
            addEvent(ActivityEvent.Kind.ACTIVITY, "Você voltou a estar ativo",
                    "Após " + minutes(Duration.between(inactiveSince, now)) + " de inatividade");
        }
        return wanted;
    }

    /** Liga e desliga os cronômetros. Pausas e retomadas por inatividade já têm o seu próprio evento. */
    private void applyRunning(Set<String> effective) {
        boolean quiet = inactivityPause || justResumed;
        justResumed = false;
        for (String key : tracker.runningKeys()) {
            if (!effective.contains(key)) {
                tracker.pause(key);
                if (!quiet) {
                    addEvent(ActivityEvent.Kind.TASK, "Tempo pausado", "Task: " + key);
                }
            }
        }
        for (String key : effective) {
            if (!tracker.isRunning(key)) {
                tracker.start(key);
                if (!quiet) {
                    addEvent(ActivityEvent.Kind.TASK, "Tempo iniciado", "Task: " + key);
                }
            }
        }
    }

    private Snapshot snapshot(ActivityState activity, Duration idle, List<JiraIssue> currentIssues) {
        List<TaskView> tasks = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        for (JiraIssue issue : currentIssues) {
            tasks.add(view(issue.key(), issue.summary(), issue.statusName(), issue.category()));
            listed.add(issue.key());
        }
        // Tasks que contaram tempo mas não vêm mais do Jira (digitadas à mão ou fora da busca).
        tracker.trackedKeys().stream().filter(key -> !listed.contains(key)).sorted().forEach(key ->
                tasks.add(view(key, "", "", StatusCategory.IN_PROGRESS)));

        return new Snapshot(
                activity,
                idle,
                featured(tasks),
                List.copyOf(tasks),
                inactivityPause,
                activeToday,
                inactiveToday,
                jiraStatus,
                jiraError,
                lastSync,
                user,
                projectLabel,
                recentEvents());
    }

    private TaskView view(String key, String summary, String statusName, StatusCategory category) {
        return new TaskView(key, summary, statusName, category, tracker.totalFor(key), tracker.isRunning(key),
                overrides.containsKey(key), tracker.runningSince(key));
    }

    /**
     * Task em destaque no painel: a que começou a contar por último; senão, a primeira em andamento.
     * Tasks que começaram no mesmo segundo desempatam pela ordem do Jira.
     */
    private static Optional<TaskView> featured(List<TaskView> tasks) {
        Optional<TaskView> latestRunning = tasks.stream()
                .filter(TaskView::running)
                .max(Comparator.comparing(task ->
                        task.runningSince().orElse(Instant.MIN).truncatedTo(ChronoUnit.SECONDS)));
        return latestRunning.or(() -> tasks.stream()
                .filter(task -> task.category() == StatusCategory.IN_PROGRESS)
                .findFirst());
    }

    private List<ActivityEvent> recentEvents() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    private void addEvent(ActivityEvent.Kind kind, String title, String detail) {
        synchronized (events) {
            events.addFirst(new ActivityEvent(clock.instant(), kind, title, detail));
            while (events.size() > MAX_EVENTS) {
                events.removeLast();
            }
        }
    }

    private static String describeSync(List<JiraIssue> fetched) {
        long inProgress = fetched.stream().filter(JiraIssue::isInProgress).count();
        return fetched.size() + (fetched.size() == 1 ? " task, " : " tasks, ") + inProgress + " em andamento";
    }

    private static String minutes(Duration duration) {
        long minutes = Math.max(1, duration.toMinutes());
        return minutes + (minutes == 1 ? " minuto" : " minutos");
    }

    private static String normalize(String issueKey) {
        return issueKey == null ? "" : issueKey.strip().toUpperCase();
    }

    public record Snapshot(
            ActivityState activity,
            Duration idleTime,
            Optional<TaskView> featuredTask,
            List<TaskView> tasks,
            boolean pausedForInactivity,
            Duration activeToday,
            Duration inactiveToday,
            JiraSyncStatus jiraStatus,
            Optional<String> jiraError,
            Optional<Instant> lastSync,
            Optional<JiraUser> user,
            Optional<String> projectLabel,
            List<ActivityEvent> recentEvents) {

        public long countByCategory(StatusCategory category) {
            return tasks.stream().filter(task -> task.category() == category).count();
        }

        public long runningCount() {
            return tasks.stream().filter(TaskView::running).count();
        }
    }
}
