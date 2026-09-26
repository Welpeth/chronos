package com.chronos.tracker.ui;

import java.time.Duration;

public final class DurationFormat {

    private DurationFormat() {
    }

    /** Formata como {@code HH:MM:SS}; horas passam de 24 em vez de virar dias. */
    public static String hms(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }
}
