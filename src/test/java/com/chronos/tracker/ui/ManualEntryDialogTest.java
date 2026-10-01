package com.chronos.tracker.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManualEntryDialogTest {

    @Test
    void emptyFieldCountsAsZero() {
        assertEquals(0, ManualEntryDialog.typedValue("", 1));
        assertEquals(0, ManualEntryDialog.typedValue("  ", 1));
        assertEquals(0, ManualEntryDialog.typedValue(null, 1));
    }

    @Test
    void typedNumberWinsAndTextKeepsThePreviousValue() {
        assertEquals(3, ManualEntryDialog.typedValue(" 3 ", 1));
        assertEquals(1, ManualEntryDialog.typedValue("abc", 1));
    }
}
