package com.chronos.tracker.system;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

/**
 * Garante um Chronos só por usuário. Fechar a janela deixa o app na bandeja, então abrir o atalho de novo
 * criava um segundo Chronos contando as mesmas tasks, e o tempo gravado dobrava. Agora o segundo só pede
 * ao primeiro para mostrar a janela e fecha. Se o primeiro estiver travado (não consegue mostrar a janela), o
 * segundo encerra o travado e abre no lugar dele.
 */
public final class SingleInstance implements AutoCloseable {

    static final String LOCK_FILE = "chronos.lock";
    static final String PORT_FILE = "chronos.port";
    private static final String SHOW = "show";
    static final String SHOWN = "ok";
    static final String FROZEN = "frozen";
    /** Quanto o segundo Chronos espera a resposta do primeiro. */
    private static final int ANSWER_TIMEOUT_MS = 20_000;

    private final FileChannel channel;
    private final FileLock lock;
    private final ServerSocket server;
    private volatile BooleanSupplier onShow = () -> true;

    private SingleInstance(FileChannel channel, FileLock lock, ServerSocket server) {
        this.channel = channel;
        this.lock = lock;
        this.server = server;
    }

    /**
     * Tenta ser o Chronos da pasta {@code dir}. Se outro já estiver aberto, pede a ele para mostrar a janela
     * e devolve vazio: quem chamou deve encerrar.
     */
    public static Optional<SingleInstance> acquire(Path dir) throws IOException {
        return acquire(dir, true);
    }

    /** Como {@link #acquire(Path)}; com {@code showOther} falso, não pede ao outro para mostrar a janela. */
    public static Optional<SingleInstance> acquire(Path dir, boolean showOther) throws IOException {
        return acquire(dir, showOther, SingleInstance::kill);
    }

    /** Como {@link #acquire(Path, boolean)}, com {@code kill} encerrando o processo de um Chronos travado. */
    static Optional<SingleInstance> acquire(Path dir, boolean showOther, LongPredicate kill) throws IOException {
        Optional<SingleInstance> instance = tryAcquire(dir);
        if (instance.isPresent() || !showOther) {
            return instance;
        }
        Answer answer = askToShow(dir);
        if (!answer.frozen() || answer.pid() <= 0 || !kill.test(answer.pid())) {
            return Optional.empty();
        }
        // O travado foi encerrado: o Windows solta o arquivo de trava logo em seguida.
        for (int attempt = 0; attempt < 20; attempt++) {
            instance = tryAcquire(dir);
            if (instance.isPresent()) {
                return instance;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Optional.empty();
    }

    private static Optional<SingleInstance> tryAcquire(Path dir) throws IOException {
        Files.createDirectories(dir);
        FileChannel channel = FileChannel.open(dir.resolve(LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            channel.close();
            return Optional.empty();
        }
        ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Files.writeString(dir.resolve(PORT_FILE), server.getLocalPort() + " " + ProcessHandle.current().pid(),
                StandardCharsets.UTF_8);
        SingleInstance instance = new SingleInstance(channel, lock, server);
        Thread listener = new Thread(instance::listen, "chronos-single-instance");
        listener.setDaemon(true);
        listener.start();
        return Optional.of(instance);
    }

    /**
     * O que fazer quando outro Chronos é aberto: normalmente, mostrar a janela. Devolve false se não conseguiu
     * (a janela não respondeu); aí o outro Chronos encerra este, que está travado, e abre no lugar.
     */
    public void onShowRequested(BooleanSupplier action) {
        onShow = action;
    }

    private void listen() {
        while (!server.isClosed()) {
            try (Socket client = server.accept();
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
                if (SHOW.equals(in.readLine())) {
                    boolean shown;
                    try {
                        shown = onShow.getAsBoolean();
                    } catch (RuntimeException e) {
                        shown = false;
                    }
                    out.println(shown ? SHOWN : FROZEN);
                }
            } catch (IOException e) {
                // Conexão que caiu no meio, ou o servidor fechando: segue ou sai do laço.
            }
        }
    }

    /** Resposta do Chronos que já estava aberto: se está travado e o processo dele. */
    record Answer(boolean frozen, long pid) {
    }

    private static Answer askToShow(Path dir) {
        long pid = -1;
        try {
            String[] parts = Files.readString(dir.resolve(PORT_FILE), StandardCharsets.UTF_8).strip().split("\\s+");
            int port = Integer.parseInt(parts[0]);
            pid = parts.length > 1 ? Long.parseLong(parts[1]) : -1;
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                 OutputStream out = socket.getOutputStream();
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(ANSWER_TIMEOUT_MS);
                out.write((SHOW + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                // Versões antigas fecham sem responder: estão vivas, só não confirmam.
                return new Answer(FROZEN.equals(in.readLine()), pid);
            }
        } catch (SocketTimeoutException e) {
            return new Answer(true, pid);
        } catch (IOException | NumberFormatException e) {
            // O outro Chronos não respondeu; ele continua na bandeja.
            return new Answer(false, pid);
        }
    }

    /** Encerra à força o processo de um Chronos travado e espera ele terminar. */
    private static boolean kill(long pid) {
        if (pid == ProcessHandle.current().pid()) {
            return false;
        }
        return ProcessHandle.of(pid).map(process -> {
            process.destroyForcibly();
            try {
                process.onExit().get(5, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                return !process.isAlive();
            }
        }).orElse(true);
    }

    @Override
    public void close() {
        try {
            server.close();
            lock.release();
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
