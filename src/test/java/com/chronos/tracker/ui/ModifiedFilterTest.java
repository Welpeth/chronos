package com.chronos.tracker.ui;

import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.tracking.TaskView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Filtro de Tarefas pela data de modificação no Jira. */
class ModifiedFilterTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @Test
    void periodsCountWholeDaysInTheLocalZone() {
        TaskView today = task(Optional.of(Instant.parse("2026-09-30T03:10:00Z"))); // 00:10 em São Paulo
        TaskView yesterday = task(Optional.of(Instant.parse("2026-09-30T02:50:00Z"))); // 23:50 do dia 29
        TaskView weekAgo = task(Optional.of(Instant.parse("2026-09-24T15:00:00Z")));
        TaskView old = task(Optional.of(Instant.parse("2026-08-01T15:00:00Z")));
        TaskView manual = task(Optional.empty());

        assertTrue(TasksPage.Modified.TODAY.accepts(today, TODAY, ZONE));
        assertFalse(TasksPage.Modified.TODAY.accepts(yesterday, TODAY, ZONE));
        assertTrue(TasksPage.Modified.YESTERDAY.accepts(yesterday, TODAY, ZONE));
        assertTrue(TasksPage.Modified.LAST_7_DAYS.accepts(weekAgo, TODAY, ZONE));
        assertFalse(TasksPage.Modified.LAST_7_DAYS.accepts(old, TODAY, ZONE));
        assertTrue(TasksPage.Modified.LAST_30_DAYS.accepts(weekAgo, TODAY, ZONE));
        assertFalse(TasksPage.Modified.LAST_30_DAYS.accepts(manual, TODAY, ZONE));
        assertTrue(TasksPage.Modified.ANY.accepts(manual, TODAY, ZONE));
    }

    private static TaskView task(Optional<Instant> updated) {
        return new TaskView("RP-1", "", "A fazer", StatusCategory.TO_DO, Duration.ZERO, false, false, Optional.empty(),
                "", true, false, true, updated);
    }
}
