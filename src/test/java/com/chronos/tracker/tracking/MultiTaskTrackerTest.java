package com.chronos.tracker.tracking;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiTaskTrackerTest {

    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final MultiTaskTracker tracker = new MultiTaskTracker(clock);

    @Test
    void parallelTasksEachGetTheFullTime() {
        tracker.start("PROJ-1");
        tracker.start("PROJ-2");
        clock.advance(Duration.ofHours(1));

        assertEquals(Duration.ofHours(1), tracker.totalFor("PROJ-1"));
        assertEquals(Duration.ofHours(1), tracker.totalFor("PROJ-2"));
        assertEquals(Set.of("PROJ-1", "PROJ-2"), tracker.runningKeys());
    }

    @Test
    void pausingOneTaskDoesNotAffectTheOthers() {
        tracker.start("PROJ-1");
        tracker.start("PROJ-2");
        clock.advance(Duration.ofMinutes(10));
        tracker.pause("PROJ-1");
        clock.advance(Duration.ofMinutes(5));

        assertEquals(Duration.ofMinutes(10), tracker.totalFor("PROJ-1"));
        assertEquals(Duration.ofMinutes(15), tracker.totalFor("PROJ-2"));
        assertFalse(tracker.isRunning("PROJ-1"));
    }

    @Test
    void totalsAccumulateAcrossIntervals() {
        tracker.start("PROJ-1");
        clock.advance(Duration.ofMinutes(4));
        tracker.pause("PROJ-1");
        clock.advance(Duration.ofMinutes(30));
        tracker.start("PROJ-1");
        clock.advance(Duration.ofMinutes(3));

        assertEquals(Duration.ofMinutes(7), tracker.totalFor("PROJ-1"));
        assertEquals(1, tracker.getCompletedEntries().size());
    }

    @Test
    void pauseRecordsTheInterval() {
        tracker.start("PROJ-1");
        clock.advance(Duration.ofMinutes(12));

        TimeEntry entry = tracker.pause("PROJ-1").orElseThrow();

        assertEquals(new TimeEntry("PROJ-1", T0, T0.plus(Duration.ofMinutes(12)), Duration.ofMinutes(12)), entry);
        assertTrue(tracker.pause("PROJ-1").isEmpty());
    }

    @Test
    void startingARunningTaskKeepsItsStart() {
        tracker.start("PROJ-1");
        clock.advance(Duration.ofMinutes(2));
        tracker.start("PROJ-1");

        assertEquals(T0, tracker.runningSince("PROJ-1").orElseThrow());
    }

    @Test
    void pauseAllClosesEveryInterval() {
        tracker.start("PROJ-1");
        tracker.start("PROJ-2");
        clock.advance(Duration.ofMinutes(1));

        assertEquals(2, tracker.pauseAll().size());
        assertTrue(tracker.runningKeys().isEmpty());
        assertEquals(Set.of("PROJ-1", "PROJ-2"), tracker.trackedKeys());
    }
}
