package com.chronos.tracker.activity;

import java.util.Locale;

/** Escolhe como detectar atividade no sistema em que o app está rodando. */
public final class ActivityMonitors {

    private ActivityMonitors() {
    }

    /**
     * No Windows, o teclado e o mouse do PC inteiro. Fora dele (ou se a API falhar), o usuário é sempre
     * considerado ativo.
     */
    public static ActivityMonitor forThisSystem() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            try {
                return new WindowsIdleMonitor();
            } catch (RuntimeException | LinkageError e) {
                System.err.println("Detecção de atividade indisponível: " + e);
            }
        }
        return new AlwaysActiveMonitor();
    }
}
