package com.chronos.tracker.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 22, 30, 5);

    @Test
    void backupIsNamedAfterTheVersionWithoutOverwriting(@TempDir Path dir) throws Exception {
        Backups backups = new Backups(dir);

        Path first = backups.fileFor("0.2.0", NOW);
        assertEquals(dir.resolve("backup").resolve("chronos-0.2.0.db"), first);
        Files.writeString(first, "antigo");

        assertEquals(dir.resolve("backup").resolve("chronos-0.2.0-20260928-223005.db"), backups.fileFor("0.2.0", NOW));
    }

    @Test
    void restoreHappensOnTheNextStartAndKeepsTheCurrentHistory(@TempDir Path dir) throws Exception {
        Backups backups = new Backups(dir);
        Path database = dir.resolve("chronos.db");
        Files.writeString(database, "atual");
        Files.writeString(Path.of(database + "-wal"), "diario da base atual");
        Path chosen = backups.dir().resolve("chronos-0.2.0.db");
        Files.writeString(chosen, "da versao 0.2.0");

        backups.scheduleRestore(chosen);
        assertEquals("atual", Files.readString(database));

        Optional<Path> restored = backups.applyPendingRestore(database, NOW);

        assertEquals(Optional.of(chosen.toAbsolutePath()), restored);
        assertEquals("da versao 0.2.0", Files.readString(database));
        assertFalse(Files.exists(Path.of(database + "-wal")));
        assertEquals("atual",
                Files.readString(backups.dir().resolve("chronos-antes-da-restauracao-20260928-223005.db")));
        // A cópia escolhida continua lá, e a marca some: a próxima abertura não restaura de novo.
        assertTrue(Files.exists(chosen));
        assertEquals(Optional.empty(), backups.applyPendingRestore(database, NOW));
    }

    @Test
    void listsOnlyDatabasesNewestFirst(@TempDir Path dir) throws Exception {
        Backups backups = new Backups(dir);
        Path older = Files.writeString(backups.dir().resolve("chronos-0.1.0.db"), "a");
        Path newer = Files.writeString(backups.dir().resolve("chronos-0.2.0.db"), "b");
        Files.writeString(backups.dir().resolve("leia-me.txt"), "c");
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2_000));

        assertEquals(java.util.List.of(newer, older), backups.list());
    }
}
