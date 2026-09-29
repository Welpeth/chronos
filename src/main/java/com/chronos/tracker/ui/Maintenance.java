package com.chronos.tracker.ui;

import com.chronos.tracker.config.AppVersion;
import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.update.Backups;
import com.chronos.tracker.update.Restarter;
import com.chronos.tracker.update.Updater;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Atualizar o Chronos e restaurar o histórico, pela aba Sistema e Histórico das Configurações. */
final class Maintenance {

    /** Atualização pronta: a cópia do histórico já foi feita e o instalador baixado. */
    record PreparedUpdate(Path backup, Path installer) {
    }

    private final HistoryStore store;
    private final Backups backups;
    private final Path downloads;
    private final Updater updater = new Updater();
    private final Runnable exitApp;

    Maintenance(HistoryStore store, Path dataDir, Runnable exitApp) {
        this.store = store;
        this.backups = new Backups(dataDir);
        this.downloads = dataDir.resolve("updates");
        this.exitApp = exitApp;
    }

    String version() {
        return AppVersion.current();
    }

    CompletableFuture<Optional<Updater.Release>> checkForUpdate() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return updater.newerThan(version());
            } catch (IOException e) {
                throw new CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        });
    }

    /** Copia o histórico atual para {@code backup/chronos-<versão atual>.db} e baixa o instalador. */
    CompletableFuture<PreparedUpdate> prepareUpdate(Updater.Release release) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path backup = backups.fileFor(version(), LocalDateTime.now());
                store.backupTo(backup);
                return new PreparedUpdate(backup, updater.download(release, downloads));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new CompletionException(e);
            }
        });
    }

    /** Abre o instalador e fecha o Chronos para ele poder trocar os arquivos. */
    void installAndExit(PreparedUpdate update) throws IOException {
        Restarter.runInstaller(update.installer());
        exitApp.run();
    }

    Path backupDir() throws IOException {
        return backups.dir();
    }

    List<Path> backupFiles() throws IOException {
        return backups.list();
    }

    /**
     * Marca {@code backup} para virar o histórico atual e reinicia o Chronos. Devolve falso se não deu para
     * reabrir sozinho (rodando fora do executável instalado): aí a troca acontece ao abrir de novo.
     */
    boolean restoreAndRestart(Path backup) throws IOException {
        backups.scheduleRestore(backup);
        if (Restarter.executable().isEmpty()) {
            return false;
        }
        Restarter.relaunch();
        exitApp.run();
        return true;
    }

    /** Reabre o Chronos; falso se não dá para reabrir sozinho (fora do executável instalado). */
    boolean restart() throws IOException {
        if (Restarter.executable().isEmpty()) {
            return false;
        }
        Restarter.relaunch();
        exitApp.run();
        return true;
    }

    void exit() {
        exitApp.run();
    }
}
