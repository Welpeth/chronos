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
import com.chronos.tracker.persistence.SqliteHistoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void reopeningTheAppRestoresTotalsFromTheStore(@TempDir Path dir) throws Exception {
        try (SqliteHistoryStore store = new SqliteHistoryStore(dir.resolve("chronos.db"), clock.getZone())) {
            jira.issues = List.of(DOING_1);
            TrackingEngine first = new TrackingEngine(new MultiTaskTracker(clock), () -> idle, classifier, jira, clock, store);
            first.pollJira();
            first.tick();
            clock.advance(Duration.ofHours(1));
            first.tick();
            first.shutdown();

            clock.advance(Duration.ofMinutes(10));
            TrackingEngine second = new TrackingEngine(new MultiTaskTracker(clock), () -> idle, classifier, jira, clock, store);
            second.pollJira();
            TrackingEngine.Snapshot snapshot = second.tick();

            assertEquals(Duration.ofHours(1), task(snapshot, "PROJ-1").totalTime());
            assertEquals(Duration.ofHours(1), snapshot.activeToday());
            assertTrue(snapshot.history().stream().anyMatch(e -> e.activeTime().equals(Duration.ofHours(1))));
        }
    }

    @Test
    void runningIntervalsAreCheckpointedInCaseTheAppDies(@TempDir Path dir) throws Exception {
        try (SqliteHistoryStore store = new SqliteHistoryStore(dir.resolve("chronos.db"), clock.getZone())) {
            jira.issues = List.of(DOING_1);
            TrackingEngine crashing = new TrackingEngine(new MultiTaskTracker(clock), () -> idle, classifier, jira, clock, store);
            crashing.pollJira();
            crashing.tick();
            for (int i = 0; i < 20; i++) {
                clock.advance(Duration.ofSeconds(5));
                crashing.tick();
            }
            // Sem shutdown: o app caiu depois de 100s contando.

            Duration saved = store.totalsByTask().get("PROJ-1");
            assertTrue(saved.compareTo(Duration.ofSeconds(60)) >= 0, "gravado: " + saved);
        }
    }

    @Test
    void idleTimeIsStoredWhenTheUserComesBack(@TempDir Path dir) throws Exception {
        try (SqliteHistoryStore store = new SqliteHistoryStore(dir.resolve("chronos.db"), clock.getZone())) {
            jira.issues = List.of(DOING_1);
            TrackingEngine withStore = new TrackingEngine(new MultiTaskTracker(clock), () -> idle, classifier, jira, clock, store);
            withStore.pollJira();
            withStore.tick();
            idle = Duration.ofMinutes(6);
            clock.advance(Duration.ofMinutes(6));
            withStore.tick();
            clock.advance(Duration.ofMinutes(10));
            withStore.tick();
            idle = Duration.ZERO;
            withStore.tick();

            assertEquals(Duration.ofMinutes(10), store.idleOn(java.time.LocalDate.of(2026, 9, 26)));
        }
    }

    @Test
    void manualTimeAddsToTheTaskAndToTheDay() throws Exception {
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();
        advance(Duration.ofHours(1));

        engine.addManual("proj-1", LocalDate.of(2026, 9, 26), Duration.ofMinutes(90), "reunião");
        TrackingEngine.Snapshot snapshot = engine.tick();

        assertEquals(Duration.ofMinutes(150), task(snapshot, "PROJ-1").totalTime());
        assertEquals(Duration.ofHours(1), snapshot.activeToday());
        assertEquals(Duration.ofMinutes(90), snapshot.manualToday());
        assertEquals(Duration.ofMinutes(150), snapshot.workedToday());
        assertEquals("reunião", snapshot.manualEntries().get(0).note());
    }

    @Test
    void manualTimeThatWouldPassEightHoursIsRejected() throws Exception {
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();
        advance(Duration.ofHours(6));
        engine.addManual("PROJ-2", LocalDate.of(2026, 9, 26), Duration.ofHours(1), "");

        InvalidManualEntryException error = assertThrows(InvalidManualEntryException.class,
                () -> engine.addManual("PROJ-2", LocalDate.of(2026, 9, 26), Duration.ofMinutes(61), ""));
        assertTrue(error.getMessage().startsWith("Tempo manual inválido"), error.getMessage());

        // Exatamente 8h ainda vale.
        engine.addManual("PROJ-2", LocalDate.of(2026, 9, 26), Duration.ofMinutes(60), "");
        assertEquals(Duration.ofHours(8), engine.tick().workedToday());
    }

    @Test
    void manualTimeNeedsTaskDurationAndPastDay() {
        LocalDate today = LocalDate.of(2026, 9, 26);
        assertThrows(InvalidManualEntryException.class, () -> engine.addManual(" ", today, Duration.ofHours(1), ""));
        assertThrows(InvalidManualEntryException.class, () -> engine.addManual("PROJ-1", today, Duration.ZERO, ""));
        assertThrows(InvalidManualEntryException.class,
                () -> engine.addManual("PROJ-1", today.plusDays(1), Duration.ofHours(1), ""));
    }

    @Test
    void manualTimeOnAnotherDayChecksThatDaysHistory(@TempDir Path dir) throws Exception {
        try (SqliteHistoryStore store = new SqliteHistoryStore(dir.resolve("chronos.db"), clock.getZone())) {
            Instant yesterday = Instant.parse("2026-09-25T09:00:00Z");
            store.saveInterval(new TimeEntry("PROJ-1", yesterday, yesterday.plus(Duration.ofHours(7)),
                    Duration.ofHours(7)), "");
            TrackingEngine withStore = new TrackingEngine(new MultiTaskTracker(clock), () -> idle, classifier, jira, clock, store);

            assertThrows(InvalidManualEntryException.class,
                    () -> withStore.addManual("PROJ-1", LocalDate.of(2026, 9, 25), Duration.ofHours(2), ""));
            withStore.addManual("PROJ-1", LocalDate.of(2026, 9, 25), Duration.ofMinutes(30), "");

            // Não entra no dia de hoje, mas soma no total da task e fica gravado.
            TrackingEngine.Snapshot snapshot = withStore.tick();
            assertEquals(Duration.ZERO, snapshot.manualToday());
            assertEquals(Duration.ofMinutes(450), store.totalsByTask().get("PROJ-1"));

            TrackingEngine reopened = new TrackingEngine(new MultiTaskTracker(clock), () -> idle, classifier, jira, clock, store);
            reopened.play("PROJ-1");
            assertEquals(Duration.ofMinutes(450), task(reopened.tick(), "PROJ-1").totalTime());
        }
    }

    @Test
    void movingToAnotherColumnPausesEvenInsideTheSameCategory() {
        engine.setWorkingStatuses(List.of("Em andamento"));
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();
        advance(Duration.ofMinutes(10));

        // "Em análise" também é da categoria "em andamento" no Jira, mas é outra coluna.
        jira.issues = List.of(new JiraIssue("PROJ-1", "Título de PROJ-1", "Em análise", StatusCategory.IN_PROGRESS));
        engine.pollJira();
        engine.tick();
        TrackingEngine.Snapshot snapshot = advance(Duration.ofMinutes(5));

        assertFalse(task(snapshot, "PROJ-1").running());
        assertEquals(Duration.ofMinutes(10), task(snapshot, "PROJ-1").totalTime());
    }

    @Test
    void withoutAutoStartOnlyPlayStartsTheTime() {
        engine.setAutoStart(false);
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();
        TrackingEngine.Snapshot snapshot = advance(Duration.ofMinutes(5));
        assertFalse(task(snapshot, "PROJ-1").running());

        engine.play("PROJ-1");
        advance(Duration.ofMinutes(10));

        // Passar para outra coluna que conta não pausa quem foi ligado no play.
        engine.setWorkingStatuses(List.of("Em andamento", "Test"));
        jira.issues = List.of(new JiraIssue("PROJ-1", "Título de PROJ-1", "Test", StatusCategory.IN_PROGRESS));
        engine.pollJira();
        engine.tick();
        snapshot = advance(Duration.ofMinutes(5));
        assertTrue(task(snapshot, "PROJ-1").running());
        assertEquals(Duration.ofMinutes(15), task(snapshot, "PROJ-1").totalTime());

        // Sair das colunas que contam pausa.
        jira.issues = List.of(new JiraIssue("PROJ-1", "Título de PROJ-1", "Concluído", StatusCategory.DONE));
        engine.pollJira();
        snapshot = advance(Duration.ofMinutes(5));
        assertFalse(task(snapshot, "PROJ-1").running());
    }

    @Test
    void typedColumnStartsTheTimeWithAutoStart() {
        engine.setWorkingStatuses(List.of("Em andamento", "Test"));
        jira.issues = List.of(new JiraIssue("PROJ-1", "Título de PROJ-1", "test", StatusCategory.IN_PROGRESS));
        engine.pollJira();
        engine.tick();
        TrackingEngine.Snapshot snapshot = advance(Duration.ofMinutes(5));
        assertTrue(task(snapshot, "PROJ-1").running());
    }

    @Test
    void typedBoardColumnCountsForTheStatusesItShows() {
        // No quadro a coluna se chama "Test", mas o status das tasks nela é "Em Teste".
        jira.columns = Map.of("Test", Set.of("Em Teste", "Reteste"));
        engine.setWorkingStatuses(List.of("test"));
        jira.issues = List.of(new JiraIssue("PROJ-1", "Título de PROJ-1", "Em Teste", StatusCategory.IN_PROGRESS),
                new JiraIssue("PROJ-2", "Título de PROJ-2", "Em andamento", StatusCategory.IN_PROGRESS));
        engine.pollJira();
        engine.tick();
        TrackingEngine.Snapshot snapshot = advance(Duration.ofMinutes(5));

        assertTrue(task(snapshot, "PROJ-1").running());
        assertTrue(task(snapshot, "PROJ-1").inWorkingColumn());
        assertFalse(task(snapshot, "PROJ-2").running());
    }

    @Test
    void aTaskThatLeavesEveryBoardLosesItsOldBoard() {
        jira.boards = Map.of("PROJ-1", List.of("Quadro A", "Quadro B"));
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        assertEquals(List.of("Quadro A", "Quadro B"), advance(Duration.ofSeconds(1)).groupsOf("PROJ-1"));

        // Saiu dos quadros no Jira: na próxima conferência some de todos, em vez de ficar no antigo.
        jira.boards = Map.of();
        advance(TrackingEngine.BOARDS_REFRESH);
        engine.pollJira();
        assertEquals(List.of(), advance(Duration.ofSeconds(1)).groupsOf("PROJ-1"));
    }

    @Test
    void columnNamesIgnoreAccentsAndCase() {
        engine.setWorkingStatuses(List.of("Em Análise"));
        jira.issues = List.of(new JiraIssue("PROJ-1", "Título de PROJ-1", "EM ANALISE", StatusCategory.IN_PROGRESS));
        engine.pollJira();
        engine.tick();

        assertTrue(task(advance(Duration.ofMinutes(5)), "PROJ-1").running());
    }

    @Test
    void someoneElsesTaskInTheColumnOnlyCountsAfterPlay() {
        engine.setWorkingStatuses(List.of("Test"));
        JiraIssue others = new JiraIssue("PROJ-2", "Título de PROJ-2", "Test", StatusCategory.IN_PROGRESS, "",
                "Ana", false);
        jira.issues = List.of(new JiraIssue("PROJ-1", "Título de PROJ-1", "Test", StatusCategory.IN_PROGRESS), others);
        engine.pollJira();
        engine.tick();
        TrackingEngine.Snapshot snapshot = advance(Duration.ofMinutes(5));
        assertTrue(task(snapshot, "PROJ-1").running());
        assertFalse(task(snapshot, "PROJ-2").running());
        assertEquals("Ana", task(snapshot, "PROJ-2").assignee());
        assertFalse(task(snapshot, "PROJ-2").mine());

        engine.play("PROJ-2");
        snapshot = advance(Duration.ofMinutes(5));
        assertTrue(task(snapshot, "PROJ-2").running());
    }

    @Test
    void lockedColumnsRefuseTimeOnTasksOutsideThem() throws Exception {
        engine.setOnlyWorkingColumns(true);
        jira.issues = List.of(DOING_1, TODO_3);
        engine.pollJira();
        engine.tick();

        engine.play("PROJ-3");
        TrackingEngine.Snapshot snapshot = advance(Duration.ofMinutes(5));
        assertFalse(task(snapshot, "PROJ-3").running());
        assertFalse(task(snapshot, "PROJ-3").timeAllowed());
        assertFalse(task(snapshot, "PROJ-3").inWorkingColumn());
        assertTrue(task(snapshot, "PROJ-1").timeAllowed());
        assertTrue(task(snapshot, "PROJ-1").inWorkingColumn());
        assertThrows(InvalidManualEntryException.class, () ->
                engine.addManual("PROJ-3", LocalDate.of(2026, 9, 26), Duration.ofMinutes(30), ""));
        // Task digitada à mão não vem do Jira: continua livre.
        engine.play("LOCAL-9");
        assertTrue(task(advance(Duration.ofMinutes(1)), "LOCAL-9").running());

        engine.setOnlyWorkingColumns(false);
        engine.play("PROJ-3");
        assertTrue(task(advance(Duration.ofMinutes(1)), "PROJ-3").running());
    }

    @Test
    void validationTagsGoOnAtPlayAndSwapWhenTheTaskLeavesTheColumn() {
        engine.setValidationLabels(List.of("em-teste"), List.of("testado"));
        jira.issues = List.of(DOING_1, TODO_3);
        engine.pollJira();
        engine.tick();
        engine.play("PROJ-3");
        engine.play("LOCAL-9");
        advance(Duration.ofMinutes(1));
        engine.pollJira();
        assertEquals(List.of("PROJ-1 +[em-teste] -[]", "PROJ-3 +[em-teste] -[]"), jira.labelChanges);

        // Pausar e voltar não repete as tags.
        engine.pause("PROJ-1");
        engine.play("PROJ-1");
        jira.issues = List.of(new JiraIssue("PROJ-1", "Título de PROJ-1", "Concluído", StatusCategory.DONE), TODO_3);
        engine.pollJira();
        engine.tick();
        engine.pollJira();
        assertEquals(List.of("PROJ-1 +[em-teste] -[]", "PROJ-3 +[em-teste] -[]", "PROJ-1 +[testado] -[em-teste]"),
                jira.labelChanges);
    }

    @Test
    void onlyTasksThatMoveIntoAColumnCountAsEntered() {
        jira.issues = List.of(DOING_1, TODO_3);
        engine.pollJira();
        engine.tick();
        // O que já estava na coluna ao abrir não conta como "entrou".
        assertEquals(List.of(), engine.drainEnteredColumns());

        jira.issues = List.of(DOING_1, new JiraIssue("PROJ-3", "Título de PROJ-3", "Em andamento",
                StatusCategory.IN_PROGRESS));
        engine.pollJira();
        engine.tick();
        assertEquals(List.of("PROJ-3"), engine.drainEnteredColumns().stream().map(JiraIssue::key).toList());
        assertEquals(List.of(), engine.drainEnteredColumns());
    }

    @Test
    void failingTagsShowUpInTheRecentActivity() {
        engine.setValidationLabels(List.of("em-teste"), List.of());
        jira.labelFailure = new JiraException("Campo labels não está na tela");
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();
        engine.pollJira();
        TrackingEngine.Snapshot snapshot = engine.tick();
        assertTrue(snapshot.recentEvents().stream()
                .anyMatch(event -> event.title().equals("Não deu para mudar as tags de PROJ-1")));
    }

    @Test
    void leavingTheColumnPausesATaskStartedByHand() {
        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();
        engine.pause("PROJ-1");
        engine.play("PROJ-1");
        advance(Duration.ofMinutes(10));

        jira.issues = List.of(new JiraIssue("PROJ-1", "Título de PROJ-1", "Concluído", StatusCategory.DONE));
        engine.pollJira();
        TrackingEngine.Snapshot snapshot = advance(Duration.ofMinutes(5));

        assertFalse(task(snapshot, "PROJ-1").running());
    }

    @Test
    void taskThatLeavesTheSearchStopsCounting() {
        jira.issues = List.of(DOING_1, DOING_2);
        engine.pollJira();
        engine.tick();
        engine.play("PROJ-2");
        advance(Duration.ofMinutes(10));

        jira.issues = List.of(DOING_1);
        engine.pollJira();
        engine.tick();
        advance(Duration.ofMinutes(5));

        assertEquals(List.of("PROJ-1"), List.copyOf(engine.tick().tasks().stream()
                .filter(TaskView::running).map(TaskView::key).toList()));
    }

    @Test
    void finishingATaskPausesItAndMovesItToDone() throws Exception {
        jira.issues = List.of(DOING_1, DOING_2);
        engine.pollJira();
        engine.tick();
        advance(Duration.ofMinutes(10));

        engine.finish("PROJ-1");
        TrackingEngine.Snapshot snapshot = advance(Duration.ofMinutes(5));

        assertFalse(task(snapshot, "PROJ-1").running());
        assertEquals(StatusCategory.DONE, task(snapshot, "PROJ-1").category());
        assertEquals(Duration.ofMinutes(10), task(snapshot, "PROJ-1").totalTime());
        assertTrue(task(snapshot, "PROJ-2").running());
        assertEquals("Task finalizada", snapshot.recentEvents().stream()
                .filter(e -> e.title().equals("Task finalizada")).findFirst().orElseThrow().title());
    }

    @Test
    void pauseAllStopsEveryRunningTask() {
        jira.issues = List.of(DOING_1, DOING_2);
        engine.pollJira();
        engine.tick();
        advance(Duration.ofMinutes(10));

        engine.pauseAll();

        assertEquals(0, advance(Duration.ofMinutes(5)).runningCount());
    }

    private static final class FakeJira implements JiraService {
        List<JiraIssue> issues = List.of();
        Map<String, Set<String>> columns = Map.of();
        Map<String, List<String>> boards;
        JiraException failure;
        JiraException labelFailure;
        final List<String> labelChanges = new ArrayList<>();

        @Override
        public void updateLabels(String issueKey, List<String> add, List<String> remove) throws JiraException {
            if (labelFailure != null) {
                throw labelFailure;
            }
            labelChanges.add(issueKey + " +" + add + " -" + remove);
        }

        @Override
        public boolean usesBoards() {
            return boards != null;
        }

        @Override
        public Map<String, List<String>> fetchBoards(List<String> issueKeys) {
            return boards;
        }

        @Override
        public Map<String, List<KanbanColumn>> fetchBoardColumns() {
            List<KanbanColumn> list = new ArrayList<>();
            columns.forEach((name, statuses) -> list.add(new KanbanColumn(name, List.copyOf(statuses))));
            return Map.of("Quadro", list);
        }

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

        @Override
        public String completeIssue(String issueKey) {
            issues = issues.stream()
                    .map(issue -> issue.key().equals(issueKey)
                            ? new JiraIssue(issue.key(), issue.summary(), "Concluído", StatusCategory.DONE)
                            : issue)
                    .toList();
            return "Concluído";
        }
    }
}
