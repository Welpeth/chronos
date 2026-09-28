package com.chronos.tracker.activity;

import java.time.Duration;

/**
 * Monitor que sempre reporta o usuário como ativo. Usado fora do Windows (por exemplo, rodando pelo Maven no
 * Linux) e quando a detecção do Windows não está disponível.
 */
public final class AlwaysActiveMonitor implements ActivityMonitor {

    @Override
    public Duration getIdleTime() {
        return Duration.ZERO;
    }
}
