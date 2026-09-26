package com.chronos.tracker.activity;

import java.time.Duration;
import java.util.Objects;

/**
 * Converte o tempo ocioso em um {@link ActivityState} usando limites configuráveis.
 *
 * <p>Abaixo de {@code possiblyIdleAfter} o usuário está ativo; entre os dois limites
 * ele pode estar lendo código ou documentação, então o tempo continua contando; a partir de
 * {@code inactiveAfter} ele é considerado inativo.
 */
public final class ActivityClassifier {

    private final Duration possiblyIdleAfter;
    private final Duration inactiveAfter;

    public ActivityClassifier(Duration possiblyIdleAfter, Duration inactiveAfter) {
        Objects.requireNonNull(possiblyIdleAfter, "possiblyIdleAfter");
        Objects.requireNonNull(inactiveAfter, "inactiveAfter");
        if (possiblyIdleAfter.isNegative() || inactiveAfter.compareTo(possiblyIdleAfter) < 0) {
            throw new IllegalArgumentException(
                    "Limites inválidos: possiblyIdleAfter=" + possiblyIdleAfter + ", inactiveAfter=" + inactiveAfter);
        }
        this.possiblyIdleAfter = possiblyIdleAfter;
        this.inactiveAfter = inactiveAfter;
    }

    public ActivityState classify(Duration idleTime) {
        if (idleTime.compareTo(inactiveAfter) >= 0) {
            return ActivityState.INACTIVE;
        }
        if (idleTime.compareTo(possiblyIdleAfter) >= 0) {
            return ActivityState.POSSIBLY_IDLE;
        }
        return ActivityState.ACTIVE;
    }
}
