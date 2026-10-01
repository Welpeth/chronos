package com.chronos.tracker.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Vigia a thread da janela. Se ela ficar presa (o Windows mostra "Não respondendo"), grava em
 * {@code travamentos/} o que cada thread estava fazendo, para descobrir a causa.
 */
public final class FreezeWatch {

    static final String FOLDER = "travamentos";
    private static final int KEEP = 5;
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Consumer<Runnable> ui;
    private final Path dir;
    private final Duration limit;
    private final Clock clock;
    private volatile boolean waiting;
    private volatile Instant sentAt;
    private boolean reported;

    /**
     * @param ui    roda uma tarefa na thread da janela (por exemplo, {@code Platform::runLater})
     * @param dir   pasta de dados do Chronos
     * @param limit quanto tempo sem resposta conta como travamento
     */
    public FreezeWatch(Consumer<Runnable> ui, Path dir, Duration limit, Clock clock) {
        this.ui = ui;
        this.dir = dir.resolve(FOLDER);
        this.limit = limit;
        this.clock = clock;
    }

    /** Começa a vigiar numa thread de fundo, conferindo a cada segundo. */
    public void start() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                check();
            }
        }, "chronos-freeze-watch");
        thread.setDaemon(true);
        thread.start();
    }

    /** Manda um sinal para a janela ou, se o último ficou sem resposta por tempo demais, grava o relatório. */
    synchronized void check() {
        Instant now = clock.instant();
        if (!waiting) {
            waiting = true;
            sentAt = now;
            ui.accept(() -> waiting = false);
            reported = false;
            return;
        }
        Duration stuck = Duration.between(sentAt, now);
        if (!reported && stuck.compareTo(limit) >= 0) {
            reported = true;
            write(stuck);
        }
    }

    private void write(Duration stuck) {
        StringBuilder out = new StringBuilder();
        out.append("Chronos travado há ").append(stuck.toSeconds()).append(" s, em ")
                .append(LocalDateTime.now(clock)).append("\n\n");
        Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
        traces.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getName().contains("JavaFX Application") ? 0 : 1))
                .forEach(e -> {
                    Thread thread = e.getKey();
                    out.append('"').append(thread.getName()).append("\" ").append(thread.getState()).append('\n');
                    for (StackTraceElement frame : e.getValue()) {
                        out.append("    at ").append(frame).append('\n');
                    }
                    out.append('\n');
                });
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("travamento-" + LocalDateTime.now(clock).format(NAME) + ".txt"),
                    out.toString(), StandardCharsets.UTF_8);
            prune();
        } catch (IOException e) {
            System.err.println("Não foi possível gravar o relatório de travamento: " + e.getMessage());
        }
    }

    /** Guarda só os últimos relatórios. */
    private void prune() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> reports = files.filter(p -> p.getFileName().toString().startsWith("travamento-"))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path old : reports.subList(Math.min(KEEP, reports.size()), reports.size())) {
                Files.deleteIfExists(old);
            }
        }
    }
}
