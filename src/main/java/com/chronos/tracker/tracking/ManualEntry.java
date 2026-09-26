package com.chronos.tracker.tracking;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Tempo inserido à mão numa task, sem horário de início e fim: só o dia e quanto tempo.
 *
 * @param id        identificador no histórico; 0 antes de gravar
 * @param createdAt quando a inserção foi feita
 */
public record ManualEntry(long id, String issueKey, String summary, LocalDate day, Duration duration, String note,
                          Instant createdAt) {
}
