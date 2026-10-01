package com.chronos.tracker.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreezeWatchTest {

    @TempDir
    Path dir;

    private Instant now = Instant.parse("2026-10-01T12:00:00Z");
    private final List<Runnable> pending = new ArrayList<>();

    private FreezeWatch watch() {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        return new FreezeWatch(pending::add, dir, Duration.ofSeconds(8), clock);
    }

    private long reports() throws Exception {
        Path folder = dir.resolve(FreezeWatch.FOLDER);
        if (!Files.exists(folder)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.count();
        }
    }

    @Test
    void responsiveWindowWritesNothing() throws Exception {
        FreezeWatch watch = watch();
        for (int i = 0; i < 20; i++) {
            watch.check();
            pending.forEach(Runnable::run);
            pending.clear();
            now = now.plusSeconds(1);
        }
        assertEquals(0, reports());
    }

    @Test
    void stuckWindowWritesOneReportWithTheThreads() throws Exception {
        FreezeWatch watch = watch();
        for (int i = 0; i <= 20; i++) {
            watch.check();
            now = now.plusSeconds(1);
        }
        assertEquals(1, reports());
        Path report;
        try (Stream<Path> files = Files.list(dir.resolve(FreezeWatch.FOLDER))) {
            report = files.findFirst().orElseThrow();
        }
        String text = Files.readString(report);
        assertTrue(text.startsWith("Chronos travado há 8 s"), text);
        assertTrue(text.contains(Thread.currentThread().getName()));
    }

    @Test
    void aNewFreezeAfterRecoveringWritesAnotherReport() throws Exception {
        FreezeWatch watch = watch();
        for (int i = 0; i <= 10; i++) {
            watch.check();
            now = now.plusSeconds(1);
        }
        pending.forEach(Runnable::run);
        pending.clear();
        for (int i = 0; i <= 10; i++) {
            watch.check();
            now = now.plusSeconds(1);
        }
        assertEquals(2, reports());
    }
}
