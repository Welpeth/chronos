package com.chronos.tracker.tracking;

import com.chronos.tracker.jira.JiraException;
import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.JiraService;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Avisa quando chega nos projetos uma task de um dos tipos escolhidos (por exemplo "Bug Cliente"), de
 * qualquer responsável.
 *
 * <p>As tasks já avisadas ficam gravadas para não avisar de novo ao reabrir o app. Quando os avisos acabaram
 * de ser ligados (ou os tipos mudaram), a primeira consulta só registra o que já existe, sem avisar: senão
 * todas as tasks dos últimos dias apareceriam de uma vez.
 */
public final class IssueAlertMonitor {

    /** Registro de que os avisos já rodaram neste banco (nunca é uma chave do Jira). */
    static final String STARTED_MARK = "#avisos-iniciados";

    private final JiraService jira;
    private final HistoryStore store;
    private final List<String> issueTypes;
    private final Clock clock;
    private final Set<String> seen = new HashSet<>();
    private boolean seedSilently;
    private boolean loaded;

    /**
     * @param seedSilently a primeira consulta só registra as tasks existentes, sem avisar
     */
    public IssueAlertMonitor(JiraService jira, HistoryStore store, List<String> issueTypes, Clock clock,
                             boolean seedSilently) {
        this.jira = jira;
        this.store = store;
        this.issueTypes = List.copyOf(issueTypes);
        this.clock = clock;
        this.seedSilently = seedSilently;
    }

    public boolean isEnabled() {
        return !issueTypes.isEmpty() && jira.isConfigured();
    }

    /** Consulta o Jira e devolve as tasks novas desde a última vez. Bloqueia; nunca chamar na thread da UI. */
    public synchronized List<JiraIssue> check() throws JiraException, HistoryStore.HistoryException {
        if (!isEnabled()) {
            return List.of();
        }
        if (!loaded) {
            Set<String> stored = store.alertedKeys();
            seen.addAll(stored);
            // Primeira vez que os avisos rodam neste banco: nada foi registrado ainda.
            seedSilently = seedSilently || stored.isEmpty();
            loaded = true;
        }
        List<JiraIssue> fresh = new ArrayList<>();
        for (JiraIssue issue : jira.fetchRecentIssuesOfTypes(issueTypes)) {
            if (seen.add(issue.key())) {
                fresh.add(issue);
            }
        }
        List<String> toMark = new ArrayList<>(fresh.stream().map(JiraIssue::key).toList());
        if (seedSilently && seen.add(STARTED_MARK)) {
            toMark.add(STARTED_MARK);
        }
        if (!toMark.isEmpty()) {
            store.markAlerted(toMark, clock.instant());
        }
        if (seedSilently) {
            seedSilently = false;
            return List.of();
        }
        return fresh;
    }
}
