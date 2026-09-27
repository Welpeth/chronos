package com.chronos.tracker.tracking;

import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.JiraService;
import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.persistence.SqliteHistoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueAlertMonitorTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-26T09:00:00Z"));
    private final FakeJira jira = new FakeJira();
    @TempDir
    Path dir;
    private SqliteHistoryStore store;

    @BeforeEach
    void open() throws Exception {
        store = new SqliteHistoryStore(dir.resolve("chronos.db"), ZoneOffset.UTC);
    }

    @AfterEach
    void close() {
        store.close();
    }

    private static JiraIssue bug(String key) {
        return new JiraIssue(key, "Cliente sem acesso", "Tarefas pendentes", StatusCategory.TO_DO, "Bug Cliente");
    }

    private IssueAlertMonitor monitor(boolean seedSilently) {
        return new IssueAlertMonitor(jira, store, List.of("Bug Cliente"), clock, seedSilently);
    }

    @Test
    void firstRunOnlyRecordsWhatAlreadyExists() throws Exception {
        jira.issues = List.of(bug("SCRUM-1"), bug("SCRUM-2"));
        IssueAlertMonitor monitor = monitor(false);

        assertEquals(List.of(), monitor.check());

        jira.issues = List.of(bug("SCRUM-3"), bug("SCRUM-1"), bug("SCRUM-2"));
        assertEquals(List.of("SCRUM-3"), keys(monitor.check()));
        assertEquals(List.of(), monitor.check());
    }

    @Test
    void reopeningTheAppAlertsOnlyWhatArrivedMeanwhile() throws Exception {
        jira.issues = List.of(bug("SCRUM-1"));
        monitor(false).check();

        jira.issues = List.of(bug("SCRUM-2"), bug("SCRUM-1"));
        assertEquals(List.of("SCRUM-2"), keys(monitor(false).check()));
    }

    @Test
    void theFirstRunAfterAnEmptyBoardStillAlertsLaterIssues() throws Exception {
        monitor(false).check();

        jira.issues = List.of(bug("SCRUM-9"));
        assertEquals(List.of("SCRUM-9"), keys(monitor(false).check()));
    }

    @Test
    void changingTheTypesSeedsSilentlyAgain() throws Exception {
        monitor(false).check();
        jira.issues = List.of(bug("SCRUM-4"));

        assertEquals(List.of(), monitor(true).check());
        assertTrue(store.alertedKeys().contains("SCRUM-4"));
    }

    @Test
    void withoutTypesNothingIsAsked() throws Exception {
        IssueAlertMonitor monitor = new IssueAlertMonitor(jira, store, List.of(), clock, false);

        assertFalse(monitor.isEnabled());
        assertEquals(List.of(), monitor.check());
        assertEquals(0, jira.calls);
    }

    private static List<String> keys(List<JiraIssue> issues) {
        return issues.stream().map(JiraIssue::key).toList();
    }

    private static final class FakeJira implements JiraService {
        List<JiraIssue> issues = List.of();
        int calls;

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<JiraIssue> fetchMyIssues() {
            return List.of();
        }

        @Override
        public List<JiraIssue> fetchRecentIssuesOfTypes(List<String> types) {
            calls++;
            return new ArrayList<>(issues);
        }
    }
}
