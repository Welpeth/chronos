package com.chronos.tracker.activity;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class WindowsIdleMonitorTest {

    @Test
    void idleTimeIsTheGapSinceTheLastInput() {
        assertEquals(Duration.ofSeconds(90), WindowsIdleMonitor.idleBetween(1_090_000, 1_000_000));
        assertEquals(Duration.ZERO, WindowsIdleMonitor.idleBetween(5_000, 5_000));
    }

    @Test
    void theTickCounterWrappingAroundAfter49DaysStillGivesTheRightGap() {
        int lastInput = (int) 0xFFFF_FC18L; // 1 segundo antes de voltar a zero
        int now = 4_000;                    // 4 segundos depois de voltar a zero
        assertEquals(Duration.ofSeconds(5), WindowsIdleMonitor.idleBetween(now, lastInput));
    }

    @Test
    void outsideWindowsTheUserCountsAsActive() {
        if (!isWindows()) {
            assertEquals(Duration.ZERO, ActivityMonitors.forThisSystem().getIdleTime());
        }
    }

    @Test
    void onWindowsTheRealApiIsUsed() {
        if (isWindows()) {
            ActivityMonitor monitor = ActivityMonitors.forThisSystem();
            assertInstanceOf(WindowsIdleMonitor.class, monitor);
            assertFalse(monitor.getIdleTime().isNegative());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
