package com.chronos.tracker.tracking;

import com.chronos.tracker.activity.ActivityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeTrackerTest {

    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");

    private MutableClock clock;
    private TimeTracker tracker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        tracker = new TimeTracker(clock);
    }

    @Test
    void countsTimeWhileRunning() {
        tracker.start("PROJ-123");
        clock.advance(Duration.ofMinutes(5));

        assertEquals(Duration.ofMinutes(5), tracker.getElapsedTime());
        assertEquals(TrackerState.RUNNING, tracker.getState());
    }

    @Test
    void pauseExcludesIdleTime() {
        tracker.start("PROJ-123");
        clock.advance(Duration.ofMinutes(5));
        tracker.pause();
        clock.advance(Duration.ofMinutes(10));
        tracker.resume();
        clock.advance(Duration.ofMinutes(2));

        assertEquals(Duration.ofMinutes(7), tracker.getElapsedTime());
    }

    @Test
    void stopReturnsEntryAndResets() {
        tracker.start("PROJ-123");
        clock.advance(Duration.ofMinutes(12));

        TimeEntry entry = tracker.stop().orElseThrow();

        assertEquals(new TimeEntry("PROJ-123", T0, T0.plus(Duration.ofMinutes(12)), Duration.ofMinutes(12)), entry);
        assertEquals(TrackerState.STOPPED, tracker.getState());
        assertEquals(Duration.ZERO, tracker.getElapsedTime());
        assertTrue(tracker.stop().isEmpty());
    }

    @Test
    void startingAnotherIssueClosesThePreviousOne() {
        tracker.start("PROJ-123");
        clock.advance(Duration.ofMinutes(12));

        Optional<TimeEntry> previous = tracker.start("PROJ-456");

        assertEquals("PROJ-123", previous.orElseThrow().issueKey());
        assertEquals(Optional.of("PROJ-456"), tracker.getCurrentIssue());
        assertEquals(Duration.ZERO, tracker.getElapsedTime());
    }

    @Test
    void pauseAndResumeAreNoOpsInTheWrongState() {
        tracker.pause();
        tracker.resume();
        assertEquals(TrackerState.STOPPED, tracker.getState());

        tracker.start("PROJ-1");
        tracker.resume();
        clock.advance(Duration.ofMinutes(1));
        tracker.pause();
        tracker.pause();
        clock.advance(Duration.ofMinutes(1));

        assertEquals(Duration.ofMinutes(1), tracker.getElapsedTime());
    }

    /** Reproduz o cenário de exemplo da especificação. */
    @Test
    void followsTheSpecScenario() {
        Optional<String> proj123 = Optional.of("PROJ-123");
        Optional<String> proj456 = Optional.of("PROJ-456");

        tracker.update(proj123, ActivityState.ACTIVE);          // 10:00 inicia PROJ-123
        clock.advance(Duration.ofMinutes(5));
        tracker.update(proj123, ActivityState.ACTIVE);          // 10:05 +5 min
        clock.advance(Duration.ofMinutes(7));
        Optional<TimeEntry> closed = tracker.update(proj456, ActivityState.ACTIVE); // 10:12 troca
        clock.advance(Duration.ofMinutes(3));
        tracker.update(proj456, ActivityState.INACTIVE);        // 10:15 pausa
        clock.advance(Duration.ofMinutes(5));
        tracker.update(proj456, ActivityState.ACTIVE);          // 10:20 continua
        clock.advance(Duration.ofMinutes(14));

        assertEquals(Duration.ofMinutes(12), closed.orElseThrow().activeTime());
        assertEquals(Duration.ofMinutes(12), tracker.totalFor("PROJ-123"));
        assertEquals(Duration.ofMinutes(17), tracker.totalFor("PROJ-456"));
    }

    @Test
    void possiblyIdleKeepsCounting() {
        tracker.update(Optional.of("PROJ-1"), ActivityState.ACTIVE);
        clock.advance(Duration.ofMinutes(3));
        tracker.update(Optional.of("PROJ-1"), ActivityState.POSSIBLY_IDLE);
        clock.advance(Duration.ofMinutes(1));

        assertEquals(Duration.ofMinutes(4), tracker.getElapsedTime());
        assertEquals(TrackerState.RUNNING, tracker.getState());
    }

    @Test
    void noIssueStopsTracking() {
        tracker.update(Optional.of("PROJ-1"), ActivityState.ACTIVE);
        clock.advance(Duration.ofMinutes(2));

        Optional<TimeEntry> closed = tracker.update(Optional.empty(), ActivityState.ACTIVE);

        assertEquals(Duration.ofMinutes(2), closed.orElseThrow().activeTime());
        assertEquals(TrackerState.STOPPED, tracker.getState());
    }

    @Test
    void totalsAccumulateAcrossSessions() {
        tracker.start("PROJ-1");
        clock.advance(Duration.ofMinutes(4));
        tracker.start("PROJ-2");
        clock.advance(Duration.ofMinutes(1));
        tracker.start("PROJ-1");
        clock.advance(Duration.ofMinutes(3));

        assertEquals(Duration.ofMinutes(7), tracker.totalFor("PROJ-1"));
        assertEquals(Duration.ofMinutes(1), tracker.totalFor("PROJ-2"));
        assertEquals(2, tracker.getCompletedEntries().size());
    }
}
