package com.chronos.tracker.tracking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Contas com intervalos de tempo. */
public final class Intervals {

    private Intervals() {
    }

    /**
     * Tempo do relógio coberto por pelo menos um intervalo. Intervalos sobrepostos (tasks contando juntas)
     * contam uma vez só: 1h com duas tasks ligadas dá 1h.
     */
    public static Duration union(List<TimeEntry> entries) {
        List<TimeEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(TimeEntry::startedAt));
        Duration total = Duration.ZERO;
        Instant currentStart = null;
        Instant currentEnd = null;
        for (TimeEntry entry : sorted) {
            if (currentEnd == null || entry.startedAt().isAfter(currentEnd)) {
                if (currentEnd != null) {
                    total = total.plus(Duration.between(currentStart, currentEnd));
                }
                currentStart = entry.startedAt();
                currentEnd = entry.endedAt();
            } else if (entry.endedAt().isAfter(currentEnd)) {
                currentEnd = entry.endedAt();
            }
        }
        if (currentEnd != null) {
            total = total.plus(Duration.between(currentStart, currentEnd));
        }
        return total;
    }
}
