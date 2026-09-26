package com.chronos.tracker.persistence;

import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.tracking.TimeEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteHistoryStoreTest {

    private static final Instant NINE = Instant.parse("2026-09-26T09:00:00Z");

    @TempDir
    Path dir;

    private static TimeEntry entry(String key, Instant start, Duration length) {
        return new TimeEntry(key, start, start.plus(length), length);
    }

    @Test
    void savingTheSameIntervalAgainUpdatesItsEnd() throws Exception {
        try (SqliteHistoryStore store = new SqliteHistoryStore(dir.resolve("chronos.db"), ZoneOffset.UTC)) {
            store.saveInterval(entry("PROJ-1", NINE, Duration.ofMinutes(10)), "Primeira");
            store.saveInterval(entry("PROJ-1", NINE, Duration.ofMinutes(30)), "");

            List<HistoryStore.StoredEntry> entries = store.entriesOn(LocalDate.of(2026, 9, 26));
            assertEquals(1, entries.size());
            assertEquals(NINE.plus(Duration.ofMinutes(30)), entries.get(0).entry().endedAt());
            // Um título vazio não apaga o que já estava gravado.
            assertEquals("Primeira", entries.get(0).summary());
        }
    }

    @Test
    void dataSurvivesReopeningTheFile() throws Exception {
        Path file = dir.resolve("sub/chronos.db");
        try (SqliteHistoryStore store = new SqliteHistoryStore(file, ZoneOffset.UTC)) {
            store.saveInterval(entry("PROJ-1", NINE, Duration.ofHours(1)), "A");
            store.saveInterval(entry("PROJ-1", NINE.plus(Duration.ofDays(1)), Duration.ofMinutes(15)), "A");
            store.saveInterval(entry("PROJ-2", NINE, Duration.ofMinutes(20)), "B");
            store.saveIdle(NINE.plus(Duration.ofHours(2)), NINE.plus(Duration.ofHours(2).plusMinutes(7)));
        }
        try (SqliteHistoryStore store = new SqliteHistoryStore(file, ZoneOffset.UTC)) {
            Map<String, Duration> totals = store.totalsByTask();
            assertEquals(Duration.ofMinutes(75), totals.get("PROJ-1"));
            assertEquals(Duration.ofMinutes(20), totals.get("PROJ-2"));

            LocalDate day = LocalDate.of(2026, 9, 26);
            List<HistoryStore.StoredEntry> today = store.entriesOn(day);
            assertEquals(2, today.size());
            assertEquals(1, store.entriesOn(day.plusDays(1)).size());
            assertTrue(store.entriesOn(day.minusDays(1)).isEmpty());
            assertEquals(Duration.ofMinutes(7), store.idleOn(day));
            assertEquals(Duration.ZERO, store.idleOn(day.plusDays(1)));
        }
    }

    @Test
    void workDateFollowsTheComputerTimeZone() throws Exception {
        // 01:00 UTC ainda é o dia anterior em São Paulo (UTC-3).
        Instant lateNight = Instant.parse("2026-09-27T01:00:00Z");
        try (SqliteHistoryStore store = new SqliteHistoryStore(dir.resolve("chronos.db"), ZoneOffset.ofHours(-3))) {
            store.saveInterval(entry("PROJ-1", lateNight, Duration.ofMinutes(5)), "A");
            assertEquals(1, store.entriesOn(LocalDate.of(2026, 9, 26)).size());
            assertTrue(store.entriesOn(LocalDate.of(2026, 9, 27)).isEmpty());
        }
    }

    @Test
    void searchFindsTaskByKeyOrTitleAcrossDates() throws Exception {
        try (SqliteHistoryStore store = new SqliteHistoryStore(dir.resolve("chronos.db"), ZoneOffset.UTC)) {
            store.saveInterval(entry("SCRUM-1", NINE, Duration.ofMinutes(10)), "Ajustar login");
            store.saveInterval(entry("SCRUM-1", NINE.plus(Duration.ofDays(3)), Duration.ofMinutes(20)), "");
            store.saveInterval(entry("SCRUM-2", NINE, Duration.ofMinutes(5)), "Relatório mensal");

            List<HistoryStore.StoredEntry> byKey = store.search("scrum-1", 50);
            assertEquals(2, byKey.size());
            // Mais recente primeiro; o intervalo sem título usa o último título gravado da task.
            assertEquals(NINE.plus(Duration.ofDays(3)), byKey.get(0).entry().startedAt());
            assertEquals("Ajustar login", byKey.get(0).summary());

            assertEquals(2, store.search("LOGIN", 50).size());
            assertEquals(1, store.search("mensal", 50).size());
            assertTrue(store.search("100%", 50).isEmpty());
            assertEquals(1, store.search("scrum", 1).size());

            assertEquals(java.util.Set.of(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 29)),
                    store.daysWithEntries());
        }
    }
}
