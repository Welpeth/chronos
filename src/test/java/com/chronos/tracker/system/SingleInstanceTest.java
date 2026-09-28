package com.chronos.tracker.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceTest {

    @TempDir
    Path dir;

    @Test
    void aSecondChronosAsksTheFirstToShowItsWindowAndGivesUp() throws Exception {
        Optional<SingleInstance> first = SingleInstance.acquire(dir);
        assertTrue(first.isPresent());
        CountDownLatch shown = new CountDownLatch(1);
        first.get().onShowRequested(shown::countDown);

        Optional<SingleInstance> second = SingleInstance.acquire(dir);

        assertTrue(second.isEmpty());
        assertTrue(shown.await(5, TimeUnit.SECONDS), "o primeiro Chronos deveria mostrar a janela");
        first.get().close();
    }

    @Test
    void afterTheFirstClosesANewOneCanStart() throws Exception {
        SingleInstance.acquire(dir).orElseThrow().close();
        Optional<SingleInstance> again = SingleInstance.acquire(dir);
        assertTrue(again.isPresent());
        again.get().close();
    }
}
