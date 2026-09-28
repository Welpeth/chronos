package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formatos de tempo usados na interface. */
public final class Formats {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEE, dd/MM/yyyy", I18n.locale());
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Formats() {
    }

    /** {@code 01:42:35}; horas passam de 24 em vez de virar dias. */
    public static String hms(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    /** {@code 1h 42m}, ou {@code 42m} abaixo de uma hora. */
    public static String hoursMinutes(Duration duration) {
        long minutes = Math.max(0, duration.toMinutes());
        if (minutes < 60) {
            return minutes + "m";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    /** Hora do dia, {@code 14:32}. */
    public static String clock(Instant instant) {
        return CLOCK.format(instant);
    }

    /** {@code sex., 25/09/2026}. */
    public static String date(LocalDate date) {
        return DATE.format(date);
    }

    /** {@code 25/09/2026}. */
    public static String shortDate(LocalDate date) {
        return SHORT_DATE.format(date);
    }

    /** {@code agora}, {@code há 1 min}, {@code há 2 h}. */
    public static String ago(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return I18n.t("agora");
        }
        if (minutes < 60) {
            return I18n.t("há {0} min", minutes);
        }
        return I18n.t("há {0} h", minutes / 60);
    }
}
