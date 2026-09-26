package com.chronos.tracker.tracking;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Onde o tempo contado fica gravado, para sobreviver a fechar o app ou a uma queda.
 *
 * <p>Gravar o mesmo intervalo (mesma task e mesmo início) de novo atualiza o fim dele, então intervalos
 * ainda abertos podem ser gravados várias vezes enquanto crescem.
 */
public interface HistoryStore {

    /** Grava ou atualiza um intervalo em que a task contou tempo. */
    void saveInterval(TimeEntry entry, String summary) throws HistoryException;

    /** Grava ou atualiza um período de ociosidade (todas as tasks pausadas por inatividade). */
    void saveIdle(Instant start, Instant end) throws HistoryException;

    /** Tempo total gravado de cada task, somando todos os dias. */
    Map<String, Duration> totalsByTask() throws HistoryException;

    /** Intervalos do dia, do mais antigo para o mais recente. */
    List<StoredEntry> entriesOn(LocalDate day) throws HistoryException;

    /** Tempo ocioso gravado no dia. */
    Duration idleOn(LocalDate day) throws HistoryException;

    /**
     * Intervalos cuja chave ou título contém o texto (sem diferenciar maiúsculas), do mais recente para o
     * mais antigo, até {@code limit} resultados.
     */
    List<StoredEntry> search(String text, int limit) throws HistoryException;

    /** Dias que têm algum intervalo gravado. */
    Set<LocalDate> daysWithEntries() throws HistoryException;

    /** Um intervalo gravado, com o título da task no momento em que foi gravado. */
    record StoredEntry(TimeEntry entry, String summary) {
    }

    /** Falha ao ler ou gravar o histórico. */
    class HistoryException extends Exception {
        public HistoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Não grava nada; usado quando não há banco configurado. */
    HistoryStore NONE = new HistoryStore() {
        @Override
        public void saveInterval(TimeEntry entry, String summary) {
        }

        @Override
        public void saveIdle(Instant start, Instant end) {
        }

        @Override
        public Map<String, Duration> totalsByTask() {
            return Map.of();
        }

        @Override
        public List<StoredEntry> entriesOn(LocalDate day) {
            return List.of();
        }

        @Override
        public Duration idleOn(LocalDate day) {
            return Duration.ZERO;
        }

        @Override
        public List<StoredEntry> search(String text, int limit) {
            return List.of();
        }

        @Override
        public Set<LocalDate> daysWithEntries() {
            return Set.of();
        }
    };
}
