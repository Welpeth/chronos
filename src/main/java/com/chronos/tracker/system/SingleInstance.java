package com.chronos.tracker.system;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Garante um Chronos só por usuário. Fechar a janela deixa o app na bandeja, então abrir o atalho de novo
 * criava um segundo Chronos contando as mesmas tasks, e o tempo gravado dobrava. Agora o segundo só pede
 * ao primeiro para mostrar a janela e fecha.
 */
public final class SingleInstance implements AutoCloseable {

    static final String LOCK_FILE = "chronos.lock";
    static final String PORT_FILE = "chronos.port";
    private static final String SHOW = "show";

    private final FileChannel channel;
    private final FileLock lock;
    private final ServerSocket server;
    private volatile Runnable onShow = () -> { };

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
            if (showOther) {
                askToShow(dir);
            }
            return Optional.empty();
        }
        ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Files.writeString(dir.resolve(PORT_FILE), Integer.toString(server.getLocalPort()), StandardCharsets.UTF_8);
        SingleInstance instance = new SingleInstance(channel, lock, server);
        Thread listener = new Thread(instance::listen, "chronos-single-instance");
        listener.setDaemon(true);
        listener.start();
        return Optional.of(instance);
    }

    /** O que fazer quando outro Chronos é aberto: normalmente, mostrar a janela. */
    public void onShowRequested(Runnable action) {
        onShow = action;
    }

    private void listen() {
        while (!server.isClosed()) {
            try (Socket client = server.accept();
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
                if (SHOW.equals(in.readLine())) {
                    onShow.run();
                }
            } catch (IOException e) {
                // Conexão que caiu no meio, ou o servidor fechando: segue ou sai do laço.
            }
        }
    }

    private static void askToShow(Path dir) {
        try {
            int port = Integer.parseInt(Files.readString(dir.resolve(PORT_FILE), StandardCharsets.UTF_8).strip());
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                 OutputStream out = socket.getOutputStream()) {
                out.write((SHOW + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | NumberFormatException e) {
            // O outro Chronos não respondeu; ele continua na bandeja.
        }
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
