package com.chronos.tracker.activity;

import java.time.Duration;

/**
 * Monitor provisório que sempre reporta o usuário como ativo.
 *
 * <p>Usado até a Fase 2, quando a detecção real de atividade no Windows substitui esta classe.
 */
public final class AlwaysActiveMonitor implements ActivityMonitor {

    @Override
    public Duration getIdleTime() {
        return Duration.ZERO;
    }
}
