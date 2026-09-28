package com.chronos.tracker;

import com.chronos.tracker.config.AppPaths;
import com.chronos.tracker.system.SingleInstance;
import com.chronos.tracker.update.Restarter;
import javafx.application.Application;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Ponto de entrada. Fica separado de {@link ChronosApp} para o JAR rodar sem module-path do JavaFX.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        try {
            boolean restarting = Arrays.asList(args).contains(Restarter.RESTART_ARG);
            Optional<SingleInstance> instance = acquire(restarting);
            if (instance.isEmpty()) {
                // Já há um Chronos aberto (talvez só na bandeja): ele mostra a janela e este fecha.
                return;
            }
            ChronosApp.singleInstance = instance.get();
        } catch (IOException e) {
            // Sem o controle de instância única, o app abre mesmo assim.
            System.err.println("Não foi possível verificar se o Chronos já está aberto: " + e.getMessage());
        }
        Application.launch(ChronosApp.class, args);
    }

    /** Reabrindo (depois de restaurar), espera o Chronos anterior terminar de fechar. */
    private static Optional<SingleInstance> acquire(boolean restarting) throws IOException {
        Optional<SingleInstance> instance = SingleInstance.acquire(AppPaths.dataDir(), !restarting);
        for (int attempt = 0; restarting && instance.isEmpty() && attempt < 60; attempt++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            instance = SingleInstance.acquire(AppPaths.dataDir(), false);
        }
        return instance;
    }
}
