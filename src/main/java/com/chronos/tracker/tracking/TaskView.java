package com.chronos.tracker.tracking;

import com.chronos.tracker.jira.StatusCategory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Uma linha da lista de tarefas: dados do Jira mais o tempo que o Chronos contou nela.
 *
 * @param running      se o tempo desta task está contando agora
 * @param manual       se o play/pause atual foi escolha do usuário, e não do status no Jira
 * @param runningSince início do intervalo atual, se a task está contando
 */
public record TaskView(
        String key,
        String summary,
        String statusName,
        StatusCategory category,
        Duration totalTime,
        boolean running,
        boolean manual,
        Optional<Instant> runningSince) {
}
