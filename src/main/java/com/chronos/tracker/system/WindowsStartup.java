package com.chronos.tracker.system;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Abrir o Chronos ao entrar no Windows, pela chave {@code Run} do usuário no registro
 * ({@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}). Não precisa de administrador.
 */
public final class WindowsStartup {

    static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    static final String VALUE_NAME = "Chronos";
    /** Argumento passado quando o Windows abre o app: começa minimizado. */
    public static final String BACKGROUND_ARG = "--background";

    private WindowsStartup() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    /**
     * O executável do Chronos, se o app está rodando pelo executável instalado. Rodando pelo Maven (java.exe)
     * não há o que registrar.
     */
    public static Optional<Path> executable() {
        return ProcessHandle.current().info().command()
                .map(Path::of)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return !name.equals("java.exe") && !name.equals("javaw.exe")
                            && !name.equals("java") && !name.equals("javaw");
                });
    }

    /** Dá para ligar a opção: Windows e rodando pelo executável. */
    public static boolean isAvailable() {
        return isWindows() && executable().isPresent();
    }

    public static boolean isEnabled() {
        if (!isWindows()) {
            return false;
        }
        try {
            return run(List.of("reg", "query", RUN_KEY, "/v", VALUE_NAME)) == 0;
        } catch (IOException e) {
            return false;
        }
    }

    public static void setEnabled(boolean enabled) throws IOException {
        if (!isWindows()) {
            throw new IOException("Disponível só no Windows.");
        }
        int exit;
        if (enabled) {
            Path exe = executable().orElseThrow(() ->
                    new IOException("Abra o Chronos pelo executável instalado para usar esta opção."));
            exit = run(List.of("reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ",
                    "/d", command(exe), "/f"));
        } else {
            if (!isEnabled()) {
                return;
            }
            exit = run(List.of("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f"));
        }
        if (exit != 0) {
            throw new IOException("O Windows recusou a alteração (código " + exit + ").");
        }
    }

    /** O que fica gravado no registro: o executável entre aspas e o argumento de abrir minimizado. */
    static String command(Path exe) {
        return "\"" + exe.toAbsolutePath() + "\" " + BACKGROUND_ARG;
    }

    private static int run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            process.getInputStream().readAllBytes();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("O comando do registro não respondeu.");
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompido", e);
        }
    }
}
