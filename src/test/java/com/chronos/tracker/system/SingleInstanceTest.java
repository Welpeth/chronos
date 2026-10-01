package com.chronos.tracker.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceTest {

    @TempDir
    Path dir;

    @Test
    void aSecondChronosAsksTheFirstToShowItsWindowAndGivesUp() throws Exception {
        Optional<SingleInstance> first = SingleInstance.acquire(dir);
        assertTrue(first.isPresent());
        CountDownLatch shown = new CountDownLatch(1);
        first.get().onShowRequested(() -> {
            shown.countDown();
            return true;
        });

        Optional<SingleInstance> second = SingleInstance.acquire(dir);

        assertTrue(second.isEmpty());
        assertTrue(shown.await(5, TimeUnit.SECONDS), "o primeiro Chronos deveria mostrar a janela");
        first.get().close();
    }

    @Test
    void aFrozenChronosIsReplacedByTheNewOne() throws Exception {
        SingleInstance frozen = SingleInstance.acquire(dir).orElseThrow();
        frozen.onShowRequested(() -> false);
        AtomicLong killed = new AtomicLong();

        Optional<SingleInstance> fresh = SingleInstance.acquire(dir, true, pid -> {
            killed.set(pid);
            frozen.close();
            return true;
        });

        assertTrue(fresh.isPresent(), "o novo Chronos deveria abrir no lugar do travado");
        assertEquals(ProcessHandle.current().pid(), killed.get());
        fresh.get().close();
    }

    @Test
    void aHealthyChronosIsNeverKilled() throws Exception {
        SingleInstance first = SingleInstance.acquire(dir).orElseThrow();
        first.onShowRequested(() -> true);

        Optional<SingleInstance> second = SingleInstance.acquire(dir, true, pid -> {
            throw new AssertionError("não deveria encerrar um Chronos que respondeu");
        });

        assertTrue(second.isEmpty());
        first.close();
    }

    @Test
    void afterTheFirstClosesANewOneCanStart() throws Exception {
        SingleInstance.acquire(dir).orElseThrow().close();
        Optional<SingleInstance> again = SingleInstance.acquire(dir);
        assertTrue(again.isPresent());
        again.get().close();
    }
}
