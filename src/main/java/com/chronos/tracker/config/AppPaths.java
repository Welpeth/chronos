package com.chronos.tracker.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Onde ficam o {@code .env} e o histórico. Rodando pelo Maven, na pasta atual. Rodando pelo executável
 * instalado, numa pasta do usuário ({@code %APPDATA%\Chronos} no Windows): a pasta do programa pode não ter
 * permissão de escrita, e ao abrir junto com o Windows a pasta atual nem é a do app.
 */
public final class AppPaths {

    private AppPaths() {
    }

    /** Pasta dos dados do app; criada se ainda não existir. */
    public static Path dataDir() {
        Optional<Path> installed = installedDataDir(
                System.getProperty("jpackage.app-path"), System.getenv("APPDATA"), System.getProperty("user.home"));
        installed.ifPresent(dir -> {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                // Sem a pasta, o erro aparece ao ler ou gravar o .env, com o caminho na mensagem.
            }
        });
        return installed.orElse(Path.of(""));
    }

    public static Path envFile() {
        return dataDir().resolve(".env");
    }

    /** Caminhos relativos (como o padrão chronos.db) ficam dentro da pasta dos dados. */
    public static Path resolve(Path path) {
        return path.isAbsolute() ? path : dataDir().resolve(path);
    }

    static Optional<Path> installedDataDir(String appPath, String appData, String userHome) {
        if (appPath == null || appPath.isBlank()) {
            return Optional.empty();
        }
        Path base = appData != null && !appData.isBlank() ? Path.of(appData) : Path.of(userHome, ".config");
        return Optional.of(base.resolve("Chronos"));
    }
}
