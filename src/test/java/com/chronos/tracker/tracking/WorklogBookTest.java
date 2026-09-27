package com.chronos.tracker.tracking;

import com.chronos.tracker.jira.JiraException;
import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.JiraService;
import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.persistence.SqliteHistoryStore;
import com.chronos.tracker.tracking.WorklogBook.Item;
import com.chronos.tracker.tracking.WorklogBook.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorklogBookTest {

    private static final Instant NINE = Instant.parse("2026-09-27T09:00:00Z");

    @TempDir
    Path dir;
    private SqliteHistoryStore store;
    private final FakeJira jira = new FakeJira();
    private final MutableClock clock = new MutableClock(NINE.plusSeconds(4 * 3600));
    private WorklogBook book;

    @BeforeEach
    void open() throws Exception {
        store = new SqliteHistoryStore(dir.resolve("chronos.db"), ZoneOffset.UTC);
        book = new WorklogBook(store, () -> jira, clock);
    }

    @AfterEach
    void close() {
        store.close();
    }

    private void worked(String key, Instant start, Duration length) throws Exception {
        store.saveInterval(new TimeEntry(key, start, start.plus(length), length), "Task " + key);
    }

    private static TaskView live(String key, Duration total, boolean running) {
        return new TaskView(key, "Task " + key, "Em andamento", StatusCategory.IN_PROGRESS, total, running, false,
                running ? Optional.of(NINE) : Optional.empty());
    }

    @Test
    void pausedTaskWaitsToBeLoggedAndRunningOneKeepsCounting() throws Exception {
        worked("SCRUM-1", NINE, Duration.ofMinutes(80));
        worked("SCRUM-2", NINE.plusSeconds(3600), Duration.ofMinutes(10));

        List<Item> items = book.items(List.of(live("SCRUM-2", Duration.ofMinutes(25), true)));

        assertEquals(List.of("SCRUM-1", "SCRUM-2"), items.stream().map(Item::key).toList());
        assertEquals(Status.PENDING, items.get(0).status());
        assertEquals(Duration.ofMinutes(80), items.get(0).pending());
        assertEquals(Status.COUNTING, items.get(1).status());
        assertEquals(Duration.ofMinutes(25), items.get(1).total());
    }

    @Test
    void loggingSendsWholeMinutesEndingWhenTheTaskWasLastWorked() throws Exception {
        worked("SCRUM-1", NINE, Duration.ofMinutes(80).plusSeconds(40));

        assertEquals(Duration.ofMinutes(80), book.log("SCRUM-1"));

        assertEquals(1, jira.logged.size());
        assertEquals(Duration.ofMinutes(80), jira.logged.get(0).spent());
        assertEquals(NINE.plusSeconds(40), jira.logged.get(0).started());
        Item item = book.items(List.of()).get(0);
        assertEquals(Status.LOGGED, item.status());
        assertEquals(Duration.ofMinutes(80), item.logged());
    }

    @Test
    void timeWorkedAfterLoggingShowsUpAgain() throws Exception {
        worked("SCRUM-1", NINE, Duration.ofMinutes(30));
        book.log("SCRUM-1");
        worked("SCRUM-1", NINE.plusSeconds(7200), Duration.ofMinutes(15));

        Item item = book.items(List.of()).get(0);

        assertEquals(Status.PENDING, item.status());
        assertEquals(Duration.ofMinutes(15), item.pending());
        assertEquals(Duration.ofMinutes(15), book.log("SCRUM-1"));
    }

    @Test
    void lessThanAMinuteIsNothingToLog() throws Exception {
        worked("SCRUM-1", NINE, Duration.ofSeconds(50));

        assertEquals(Status.LOGGED, book.items(List.of()).get(0).status());
        assertThrows(JiraException.class, () -> book.log("SCRUM-1"));
        assertEquals(List.of(), jira.logged);
    }

    @Test
    void whenJiraRefusesNothingIsMarkedAsLogged() throws Exception {
        worked("SCRUM-1", NINE, Duration.ofMinutes(20));
        jira.failure = new JiraException("O Jira recusou");

        assertThrows(JiraException.class, () -> book.log("SCRUM-1"));

        assertEquals(Status.PENDING, book.items(List.of()).get(0).status());
    }

    private record Logged(String key, Duration spent, Instant started) {
    }

    private static final class FakeJira implements JiraService {
        final List<Logged> logged = new ArrayList<>();
        JiraException failure;

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<JiraIssue> fetchMyIssues() {
            return List.of();
        }

        @Override
        public String addWorklog(String issueKey, Duration spent, Instant started) throws JiraException {
            if (failure != null) {
                throw failure;
            }
            logged.add(new Logged(issueKey, spent, started));
            return "id-" + logged.size();
        }
    }
}
