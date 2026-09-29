package com.chronos.tracker.tracking;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tags (labels do Jira) da validação: quando o tempo de uma task começa, ela recebe as tags do play (ex.:
 * em-teste); quando sai das colunas monitoradas ou é finalizada, troca as do play pelas de fim (ex.: testado).
 *
 * <p>O engine só anota o que mudou; as chamadas ao Jira saem por {@link #drain()} na thread que consulta o Jira.
 */
public final class ValidationLabels {

    /** Labels a pôr e a tirar de uma issue. */
    public record Change(String issueKey, List<String> add, List<String> remove) {
    }

    private volatile List<String> onPlay = List.of();
    private volatile List<String> onDone = List.of();
    private final Set<String> tagged = ConcurrentHashMap.newKeySet();
    private final Queue<Change> pending = new ConcurrentLinkedQueue<>();

    public void configure(List<String> playLabels, List<String> doneLabels) {
        onPlay = List.copyOf(playLabels);
        onDone = List.copyOf(doneLabels);
    }

    boolean enabled() {
        return !onPlay.isEmpty() || !onDone.isEmpty();
    }

    /** O tempo da task começou: põe as tags do play, uma vez até ela ser finalizada. */
    void started(String issueKey) {
        if (enabled() && tagged.add(issueKey) && !onPlay.isEmpty()) {
            pending.add(new Change(issueKey, onPlay, List.of()));
        }
    }

    /** A task saiu das colunas ou foi finalizada: troca as tags do play pelas de fim. */
    void finished(String issueKey) {
        if (!tagged.remove(issueKey)) {
            return;
        }
        List<String> remove = new ArrayList<>(onPlay);
        remove.removeAll(onDone);
        if (!onDone.isEmpty() || !remove.isEmpty()) {
            pending.add(new Change(issueKey, onDone, List.copyOf(remove)));
        }
    }

    /** Tira da fila as mudanças ainda não enviadas ao Jira. */
    public List<Change> drain() {
        List<Change> changes = new ArrayList<>();
        Change change;
        while ((change = pending.poll()) != null) {
            changes.add(change);
        }
        return changes;
    }

    /** Label do Jira não aceita espaço: "Em teste" vira "Em-teste". */
    public static String normalize(String label) {
        return label.strip().replaceAll("\\s+", "-");
    }

    /** Ex.: "+testado −em-teste". */
    static String describe(Change change) {
        List<String> parts = new ArrayList<>();
        change.add().forEach(label -> parts.add("+" + label));
        change.remove().forEach(label -> parts.add("−" + label));
        return String.join(" ", parts);
    }
}
