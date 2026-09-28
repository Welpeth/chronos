package com.chronos.tracker.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Abre o Chronos de novo (só no executável instalado, que sabe o próprio caminho) ou um instalador. */
public final class Restarter {

    /** Argumento que faz o novo Chronos esperar o anterior fechar, em vez de só mostrar a janela dele. */
    public static final String RESTART_ARG = "--restart";

    private Restarter() {
    }

    public static Optional<Path> executable() {
        String appPath = System.getProperty("jpackage.app-path");
        return appPath == null || appPath.isBlank() ? Optional.empty() : Optional.of(Path.of(appPath));
    }

    /** Inicia um novo Chronos, que espera este fechar. Quem chama deve fechar o app logo em seguida. */
    public static void relaunch() throws IOException {
        Path exe = executable().orElseThrow(() -> new IOException("Reabra o Chronos para terminar."));
        List<String> command = new ArrayList<>(List.of(exe.toString(), RESTART_ARG));
        new ProcessBuilder(command).start();
    }

    /** Roda o instalador baixado; ele atualiza o Chronos depois que este fechar. */
    public static void runInstaller(Path installer) throws IOException {
        new ProcessBuilder(installer.toString()).start();
    }
}
