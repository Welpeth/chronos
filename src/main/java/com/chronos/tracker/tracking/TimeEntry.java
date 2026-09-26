package com.chronos.tracker.tracking;

import java.time.Duration;
import java.time.Instant;

/**
 * Um bloco de trabalho encerrado em uma issue do Jira.
 *
 * @param issueKey   chave da issue, por exemplo {@code PROJ-123}
 * @param startedAt  quando o tracking da issue começou
 * @param endedAt    quando o tracking da issue foi encerrado
 * @param activeTime tempo efetivamente trabalhado, sem as pausas por inatividade
 */
public record TimeEntry(String issueKey, Instant startedAt, Instant endedAt, Duration activeTime) {
}
