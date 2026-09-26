package com.chronos.tracker.tracking;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntervalsTest {

    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");

    private static TimeEntry at(int startMinute, int endMinute) {
        Instant start = T0.plus(Duration.ofMinutes(startMinute));
        Instant end = T0.plus(Duration.ofMinutes(endMinute));
        return new TimeEntry("PROJ-1", start, end, Duration.between(start, end));
    }

    @Test
    void overlappingIntervalsCountTheClockOnce() {
        assertEquals(Duration.ofMinutes(90), Intervals.union(List.of(at(0, 60), at(30, 90))));
    }

    @Test
    void separateIntervalsAreAdded() {
        assertEquals(Duration.ofMinutes(40), Intervals.union(List.of(at(50, 70), at(0, 20))));
    }

    @Test
    void emptyListIsZero() {
        assertEquals(Duration.ZERO, Intervals.union(List.of()));
    }
}
