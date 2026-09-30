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
 * @param assignee     responsável no Jira (vazio se não tem ou não veio do Jira)
 * @param mine         se é do usuário; falso para as de outros responsáveis que aparecem pela coluna
 * @param inWorkingColumn se a task do Jira está numa das colunas monitoradas (as que contam tempo)
 * @param timeAllowed  se aceita tempo; falso só com a trava das colunas ligada e a task fora delas
 * @param updated      última modificação no Jira (vazio para tasks fora do Jira)
 */
public record TaskView(
        String key,
        String summary,
        String statusName,
        StatusCategory category,
        Duration totalTime,
        boolean running,
        boolean manual,
        Optional<Instant> runningSince,
        String assignee,
        boolean mine,
        boolean inWorkingColumn,
        boolean timeAllowed,
        Optional<Instant> updated) {

    public TaskView(String key, String summary, String statusName, StatusCategory category, Duration totalTime,
                    boolean running, boolean manual, Optional<Instant> runningSince, String assignee, boolean mine,
                    boolean inWorkingColumn, boolean timeAllowed) {
        this(key, summary, statusName, category, totalTime, running, manual, runningSince, assignee, mine,
                inWorkingColumn, timeAllowed, Optional.empty());
    }

    public TaskView(String key, String summary, String statusName, StatusCategory category, Duration totalTime,
                    boolean running, boolean manual, Optional<Instant> runningSince) {
        this(key, summary, statusName, category, totalTime, running, manual, runningSince, "", true,
                category == StatusCategory.IN_PROGRESS, true);
    }
}
