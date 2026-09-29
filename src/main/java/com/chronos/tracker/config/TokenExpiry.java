package com.chronos.tracker.config;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

/**
 * Data de validade do API token do Jira. A Atlassian não informa essa data pela API com o próprio token, então
 * o usuário digita a data que aparece em id.atlassian.com ao criar o token e ela fica no {@code .env}.
 */
public final class TokenExpiry {

    public static final String KEY = "JIRA_API_TOKEN_EXPIRES";
    /** A partir de quantos dias antes o vencimento vira aviso. */
    public static final int WARN_DAYS = 14;

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public enum Level { OK, SOON, EXPIRED }

    private TokenExpiry() {
    }

    /** Data gravada no .env (formato 2026-12-31), se houver e for válida. */
    public static Optional<LocalDate> from(Map<String, String> env) {
        String value = env.getOrDefault(KEY, "").strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static Level level(LocalDate expires, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, expires);
        if (days < 0) {
            return Level.EXPIRED;
        }
        return days <= WARN_DAYS ? Level.SOON : Level.OK;
    }

    /** {@code faltam 95 dias}, {@code vence amanhã}, {@code venceu há 3 dias}. */
    public static String remaining(LocalDate expires, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, expires);
        if (days == 0) {
            return I18n.t("vence hoje");
        }
        if (days == 1) {
            return I18n.t("vence amanhã");
        }
        if (days > 1) {
            return I18n.t("faltam {0} dias", days);
        }
        return days == -1 ? I18n.t("venceu ontem") : I18n.t("venceu há {0} dias", -days);
    }

    /** {@code Data de validade: 31/12/2026 · faltam 95 dias}. */
    public static String describe(LocalDate expires, LocalDate today) {
        return I18n.t("Data de validade: {0} · {1}", DISPLAY.format(expires), remaining(expires, today));
    }
}
