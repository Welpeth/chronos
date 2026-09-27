package com.chronos.tracker.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenExpiryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 27);

    @Test
    void readsTheIsoDateFromTheEnvFile() {
        assertEquals(Optional.of(LocalDate.of(2026, 12, 31)), TokenExpiry.from(Map.of(TokenExpiry.KEY, "2026-12-31")));
        assertEquals(Optional.empty(), TokenExpiry.from(Map.of(TokenExpiry.KEY, "31/12")));
        assertEquals(Optional.empty(), TokenExpiry.from(Map.of()));
    }

    @Test
    void describesTheDaysLeft() {
        assertEquals("Data de validade: 31/12/2026 · faltam 95 dias",
                TokenExpiry.describe(LocalDate.of(2026, 12, 31), TODAY));
        assertEquals("vence hoje", TokenExpiry.remaining(TODAY, TODAY));
        assertEquals("vence amanhã", TokenExpiry.remaining(TODAY.plusDays(1), TODAY));
        assertEquals("venceu ontem", TokenExpiry.remaining(TODAY.minusDays(1), TODAY));
        assertEquals("venceu há 3 dias", TokenExpiry.remaining(TODAY.minusDays(3), TODAY));
    }

    @Test
    void warnsTwoWeeksBefore() {
        assertEquals(TokenExpiry.Level.OK, TokenExpiry.level(TODAY.plusDays(15), TODAY));
        assertEquals(TokenExpiry.Level.SOON, TokenExpiry.level(TODAY.plusDays(14), TODAY));
        assertEquals(TokenExpiry.Level.SOON, TokenExpiry.level(TODAY, TODAY));
        assertEquals(TokenExpiry.Level.EXPIRED, TokenExpiry.level(TODAY.minusDays(1), TODAY));
    }
}
