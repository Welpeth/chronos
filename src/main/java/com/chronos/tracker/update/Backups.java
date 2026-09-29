package com.chronos.tracker.update;

import com.chronos.tracker.config.I18n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Cópias do histórico na pasta {@code backup}, ao lado do {@code .env}. Antes de atualizar, o histórico vira
 * {@code chronos-<versão>.db} ali; a restauração troca o histórico atual por uma dessas cópias.
 *
 * <p>O histórico está aberto enquanto o app roda, então a restauração só é marcada ({@code restore.pending}) e
 * acontece na próxima abertura, antes de o histórico ser aberto.
 */
public final class Backups {

    static final String PENDING = "restore.pending";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dataDir;

    public Backups(Path dataDir) {
        this.dataDir = dataDir;
    }

    public Path dir() throws IOException {
        return Files.createDirectories(dataDir.resolve("backup"));
    }

    /** Onde gravar a cópia da versão: {@code chronos-0.2.0.db}, ou com data e hora se já existe uma. */
    public Path fileFor(String version, LocalDateTime now) throws IOException {
        Path plain = dir().resolve("chronos-" + version + ".db");
        return Files.exists(plain) ? dir().resolve("chronos-" + version + "-" + STAMP.format(now) + ".db") : plain;
    }

    /** Cópias na pasta, da mais nova para a mais antiga. */
    public List<Path> list() throws IOException {
        try (Stream<Path> files = Files.list(dir())) {
            return files.filter(file -> file.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparing(Backups::modified).reversed())
                    .toList();
        }
    }

    /** Marca {@code backup} para virar o histórico atual na próxima vez que o Chronos abrir. */
    public void scheduleRestore(Path backup) throws IOException {
        Files.writeString(dataDir.resolve(PENDING), backup.toAbsolutePath().toString());
    }

    /**
     * Se há restauração marcada, guarda o histórico atual em {@code backup} (para dar para voltar) e põe a cópia
     * escolhida no lugar. Chamar antes de abrir o histórico. Devolve a cópia restaurada.
     */
    public Optional<Path> applyPendingRestore(Path database, LocalDateTime now) throws IOException {
        Path marker = dataDir.resolve(PENDING);
        if (!Files.exists(marker)) {
            return Optional.empty();
        }
        Path chosen = Path.of(Files.readString(marker).strip());
        Files.delete(marker);
        if (!Files.isRegularFile(chosen)) {
            throw new IOException(I18n.t("A cópia escolhida para restaurar não existe mais: {0}", chosen));
        }
        if (Files.exists(database)) {
            Files.copy(database, dir().resolve("chronos-antes-da-restauracao-" + STAMP.format(now) + ".db"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        // Restos do diário do SQLite são da base antiga: não podem ser aplicados na restaurada.
        Files.deleteIfExists(Path.of(database + "-wal"));
        Files.deleteIfExists(Path.of(database + "-shm"));
        Files.deleteIfExists(Path.of(database + "-journal"));
        Files.copy(chosen, database, StandardCopyOption.REPLACE_EXISTING);
        return Optional.of(chosen);
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
