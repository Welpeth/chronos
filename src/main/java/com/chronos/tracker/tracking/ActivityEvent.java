package com.chronos.tracker.tracking;

import java.time.Instant;

/**
 * Algo que aconteceu no tracking, mostrado em "Atividade recente".
 *
 * @param at     quando aconteceu
 * @param kind   tipo, usado pela UI para escolher a cor
 * @param title  frase curta, por exemplo "Task alterada"
 * @param detail complemento, por exemplo "De PROJ-124 para PROJ-123"
 */
public record ActivityEvent(Instant at, Kind kind, String title, String detail) {

    public enum Kind {
        SYNC,
        ACTIVITY,
        TASK,
        ERROR
    }
}
