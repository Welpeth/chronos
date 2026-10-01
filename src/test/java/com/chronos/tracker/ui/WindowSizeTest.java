package com.chronos.tracker.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowSizeTest {

    @Test
    void bigScreenKeepsTheNormalSize() {
        assertEquals(new WindowSize(1320, 860, 1100, 700, false), WindowSize.fit(1920, 1040));
    }

    @Test
    void smallScreenOpensMaximizedAndFitsTheMinimum() {
        // 1920x1080 com zoom de 150%: sobram 1280x680 sem a barra de tarefas.
        assertEquals(new WindowSize(1280, 680, 1100, 680, true), WindowSize.fit(1280, 680));
        assertEquals(new WindowSize(1024, 728, 1024, 700, true), WindowSize.fit(1024, 728));
    }
}
