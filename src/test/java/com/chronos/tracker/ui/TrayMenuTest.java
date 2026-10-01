package com.chronos.tracker.ui;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TrayMenuTest {

    private static final Rectangle2D SCREEN = new Rectangle2D(0, 0, 1920, 1040);

    @Test
    void opensAboveAndToTheLeftOfTheClickNearTheBottomTaskbar() {
        // Clique no ícone perto do canto inferior direito.
        assertArrayEquals(new double[] {1900 - 354 + 12, 1040 - 300},
                TrayMenu.place(1900, 1040, 354, 300, SCREEN), 0.01);
    }

    @Test
    void opensBelowWhenTheTaskbarIsOnTop() {
        Rectangle2D belowTopBar = new Rectangle2D(0, 40, 1920, 1040);
        assertArrayEquals(new double[] {1900 - 354 + 12, 40},
                TrayMenu.place(1900, 20, 354, 300, belowTopBar), 0.01);
    }

    @Test
    void staysInsideTheScreenOnTheLeftEdge() {
        assertArrayEquals(new double[] {0, 1040 - 300},
                TrayMenu.place(5, 1040, 354, 300, SCREEN), 0.01);
    }
}
