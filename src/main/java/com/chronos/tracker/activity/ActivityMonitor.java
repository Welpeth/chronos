package com.chronos.tracker.activity;

import java.time.Duration;

/**
 * Fonte do tempo desde a última interação do usuário (mouse, teclado, etc.).
 */
public interface ActivityMonitor {

    Duration getIdleTime();
}
