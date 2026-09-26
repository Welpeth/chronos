package com.chronos.tracker.tracking;

import com.chronos.tracker.activity.ActivityClassifier;
import com.chronos.tracker.jira.JiraAuthException;
import com.chronos.tracker.jira.JiraException;
import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.JiraQueryException;
import com.chronos.tracker.jira.JiraService;
import com.chronos.tracker.jira.JiraSyncStatus;
import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.jira.UnconfiguredJiraService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackingEngineTest {

    private static final JiraIssue DOING_1 = issue("PROJ-1", StatusCategory.IN_PROGRESS);
    private static final JiraIssue DOING_2 = issue("PROJ-2", StatusCategory.IN_PROGRESS);
    private static final JiraIssue TODO_3 = issue("PROJ-3", StatusCategory.TO_DO);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-26T10:00:00Z"));
    private final ActivityClassifier classifier = new ActivityClassifier(Duration.ofMinutes(2), Duration.ofMinutes(5));
    private final FakeJira jira = new FakeJira();
    private Duration idle = Duration.ZERO;

    private final TrackingEngine engine =
            new TrackingEngine(new MultiTaskTracker(clock), () -> idle, classifier, jira, clock);

    private static JiraIssue issue(String key, StatusCategory category) {
        String status = switch (category) {
            case TO_DO -> "A fazer";
            case IN_PROGRESS -> "Em andamento";
            case DONE -> "Concluído";
        };
        return new JiraIssue(key, "Título de " + key, status, category);
    }

    private TaskView task(TrackingEngine.Snapshot snapshot, String key) {
        return snapshot.tasks().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
    }

    private TrackingEngine.Snapshot advance(Duration duration) {
        clock.advance(duration);
        return engine.tick();
    }

    @Test
    void everyTaskInProgressCountsInParallel() {
        jira.issues = List.of(DOING_1, DOING_2, TODO_3);
        engine.pollJira();
        engine.tick();

        TrackingEngine.Snapshot snapshot = advance(Duration.ofHours(1));

        assertEquals(Duration.ofHours(1), task(snapshot, "PROJ-1").totalTime());
        assertEquals(Duration.ofHours(1), task(snapshot, "PROJ-2").totalTime());
        assertEquals(Duration.ZERO, task(snapshot, "PROJ-3").totalTime());
        // O dia conta o relógio, não a soma das tasks.
        assertEquals(Duration.ofHours(1), snapshot.activeToday());
        assertEquals(2, snapshot.runningCount());
    }

    @Test
    void pausingOneTaskKeepsTheOthersRunning() {
        jira.issues = List.of(DOING_1, DOING_2);
        engine.pollJira();
        engine.tick();
        advance(Duration.ofMinutes(10));

        engine.pause("PROJ-1");
        TrackingEngine.Snapshot snapshot = advance(Duration.ofMinutes(20));

        assertEquals(Duration.ofMinutes(10), task(snapshot, "PROJ-1").totalTime());
        assertEquals(Duration.ofMinutes(30), task(snapshot, "PROJ-2").totalTime());
        assertTrue(task(snapshot, "PROJ-1").manual());
    }

    @Test
    void playStartsATaskThatIsNotInProgress() {
        jira.issues = List.of(TODO_3);
        engine.pollJira();
        engine.play("proj-3");
        engine.tick();

        TrackingEngine.Snapshot snapshot = advance(Duration.ofMinutes(5));

        assertTrue(task(snapshot, "PROJ-3").running());
        assertEquals(Duration.ofMinutes(5), task(snapshot, "PROJ-3").totalTime());
    }

    @Test
    void manualChoiceLastsUntilTheJiraStatusChanges() {
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();
        engine.pause("PROJ-1");
        advance(Duration.ofMinutes(1));

        // Mesmo status no Jira: a pausa manual continua valendo.
        engine.pollJira();
        assertFalse(task(advance(Duration.ofMinutes(1)), "PROJ-1").running());

        // Saiu e voltou para "em andamento": o app volta a seguir o Jira.
        jira.issues = List.of(issue("PROJ-1", StatusCategory.TO_DO));
        engine.pollJira();
        advance(Duration.ofSeconds(1));
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        TrackingEngine.Snapshot snapshot = advance(Duration.ofSeconds(1));

        assertTrue(task(snapshot, "PROJ-1").running());
        assertFalse(task(snapshot, "PROJ-1").manual());
    }

    @Test
    void inactivityPausesEverythingAndResumesTogether() {
        jira.issues = List.of(DOING_1, DOING_2);
        engine.pollJira();
        engine.tick();
        advance(Duration.ofMinutes(10));

        idle = Duration.ofMinutes(6);
        TrackingEngine.Snapshot paused = advance(Duration.ofSeconds(1));
        assertTrue(paused.pausedForInactivity());
        assertEquals(0, paused.runningCount());

        advance(Duration.ofMinutes(20));
        idle = Duration.ZERO;
        TrackingEngine.Snapshot back = advance(Duration.ofSeconds(1));

        assertEquals(2, back.runningCount());
        // Conta a partir do tick em que a inatividade foi detectada até o tick da volta.
        assertEquals(Duration.ofMinutes(20).plusSeconds(1), back.inactiveToday());
        assertEquals("Você voltou a estar ativo", back.recentEvents().get(0).title());
    }

    @Test
    void featuredTaskIsTheLastOneStarted() {
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();
        advance(Duration.ofMinutes(1));
        engine.play("PROJ-9");

        TrackingEngine.Snapshot snapshot = advance(Duration.ofSeconds(1));

        assertEquals("PROJ-9", snapshot.featuredTask().orElseThrow().key());
    }

    @Test
    void tasksStartedTogetherFeatureTheFirstFromJira() {
        jira.issues = List.of(DOING_1, DOING_2);
        engine.pollJira();
        engine.tick();

        assertEquals("PROJ-1", advance(Duration.ofMinutes(1)).featuredTask().orElseThrow().key());
    }

    @Test
    void historyListsClosedAndOpenIntervalsNewestFirst() {
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();
        advance(Duration.ofMinutes(10));
        engine.pause("PROJ-1");
        advance(Duration.ofMinutes(5));
        engine.play("PROJ-1");

        List<TimeEntry> history = advance(Duration.ofMinutes(2)).history();

        assertEquals(2, history.size());
        assertEquals(Duration.ofMinutes(2), history.get(0).activeTime());
        assertEquals(Duration.ofMinutes(10), history.get(1).activeTime());
    }

    @Test
    void jiraFailureKeepsTheLastKnownTasks() {
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();

        jira.failure = new JiraException("offline");
        engine.pollJira();
        TrackingEngine.Snapshot snapshot = advance(Duration.ofMinutes(2));

        assertTrue(task(snapshot, "PROJ-1").running());
        assertEquals(JiraSyncStatus.ERROR, snapshot.jiraStatus());
    }

    @Test
    void errorKindsAreReportedSeparately() {
        jira.failure = new JiraAuthException("401");
        engine.pollJira();
        assertEquals(JiraSyncStatus.AUTH_ERROR, engine.tick().jiraStatus());

        jira.failure = new JiraQueryException("O valor 'PROJ' não existe");
        engine.pollJira();
        TrackingEngine.Snapshot snapshot = engine.tick();
        assertEquals(JiraSyncStatus.QUERY_ERROR, snapshot.jiraStatus());
        assertEquals("O valor 'PROJ' não existe", snapshot.jiraError().orElseThrow());
    }

    @Test
    void unconfiguredJiraStillAllowsManualTasks() {
        TrackingEngine offline = new TrackingEngine(
                new MultiTaskTracker(clock), () -> idle, classifier, new UnconfiguredJiraService(), clock);
        offline.pollJira();
        offline.play("PROJ-7");
        offline.tick();
        clock.advance(Duration.ofSeconds(30));

        TrackingEngine.Snapshot snapshot = offline.tick();

        assertEquals(JiraSyncStatus.NOT_CONFIGURED, snapshot.jiraStatus());
        assertEquals(Duration.ofSeconds(30), snapshot.tasks().get(0).totalTime());
    }

    @Test
    void newDayResetsDailyTotals() {
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();
        advance(Duration.ofHours(2));

        TrackingEngine.Snapshot nextDay = advance(Duration.ofDays(1));

        assertEquals(Duration.ZERO, nextDay.activeToday());
    }

    private static final class FakeJira implements JiraService {
        List<JiraIssue> issues = List.of();
        JiraException failure;

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<JiraIssue> fetchMyIssues() throws JiraException {
            if (failure != null) {
                throw failure;
            }
            return issues;
        }
    }
}
