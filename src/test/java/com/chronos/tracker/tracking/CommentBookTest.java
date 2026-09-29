package com.chronos.tracker.tracking;

import com.chronos.tracker.jira.JiraException;
import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.JiraService;
import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.persistence.SqliteHistoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentBookTest {

    @TempDir
    Path dir;

    private final FakeJira jira = new FakeJira();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-29T13:00:00Z"), ZoneOffset.UTC);

    @Test
    void templateCountsAsDoneButCanBeCompletedLater() throws Exception {
        try (SqliteHistoryStore store = new SqliteHistoryStore(dir.resolve("chronos.db"), ZoneOffset.UTC)) {
            CommentBook book = new CommentBook(dir.resolve("comment-template.md"), store, () -> jira, clock);
            List<TaskView> live = List.of(task("RP-1", true), task("RP-2", true), task("RP-3", false));

            assertEquals(List.of(CommentBook.Status.PENDING, CommentBook.Status.PENDING),
                    book.items(live).stream().map(CommentBook.Item::status).toList());

            // Sem template escrito, nada vai para o Jira.
            assertEquals(Optional.empty(), book.addTemplate("RP-1", "Login"));
            book.saveTemplate(":light_bulb_on: **Feito**");
            assertTrue(book.addTemplate("RP-1", "Login").isPresent());
            // Só uma vez por task.
            assertEquals(Optional.empty(), book.addTemplate("RP-1", "Login"));
            assertEquals(List.of("add RP-1 :light_bulb_on: **Feito**"), jira.calls);

            List<CommentBook.Item> items = book.items(live);
            assertEquals("RP-2", items.get(0).key());
            assertEquals(CommentBook.Status.PENDING, items.get(0).status());
            assertEquals(CommentBook.Status.TEMPLATE, items.get(1).status());

            // Completar o template edita o mesmo comentário no Jira.
            book.save("RP-1", "Login", "Pronto");
            book.save("RP-2", "Tela", "Feito");
            assertEquals(List.of("add RP-1 :light_bulb_on: **Feito**", "update RP-1 c1 Pronto", "add RP-2 Feito"),
                    jira.calls);
            assertEquals(List.of(CommentBook.Status.COMMENTED, CommentBook.Status.COMMENTED),
                    book.items(live).stream().map(CommentBook.Item::status).toList());
            assertEquals(2, book.history().size());
        }
    }

    private static TaskView task(String key, boolean inColumn) {
        return new TaskView(key, "Resumo " + key, "Test", StatusCategory.IN_PROGRESS, Duration.ZERO, false, false,
                Optional.empty(), "", true, inColumn, true);
    }

    private static final class FakeJira implements JiraService {
        final List<String> calls = new ArrayList<>();
        private int next = 1;

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<JiraIssue> fetchMyIssues() {
            return List.of();
        }

        @Override
        public String addComment(String issueKey, String markdown) {
            calls.add("add " + issueKey + " " + markdown);
            return "c" + next++;
        }

        @Override
        public void updateComment(String issueKey, String commentId, String markdown) throws JiraException {
            calls.add("update " + issueKey + " " + commentId + " " + markdown);
        }
    }
}
