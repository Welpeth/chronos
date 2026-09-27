package com.chronos.tracker.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PagedListDialogTest {

    @Test
    void twentyFiveItemsPerPage() {
        assertEquals(1, PagedListDialog.pageCount(0));
        assertEquals(1, PagedListDialog.pageCount(25));
        assertEquals(2, PagedListDialog.pageCount(26));
        assertEquals(8, PagedListDialog.pageCount(200));
    }

    @Test
    void pageTextShowsWhichItemsAreOnScreen() {
        assertEquals("Página 1 de 1", PagedListDialog.pageText(0, 0));
        assertEquals("Página 1 de 3 · 1 a 25 de 60", PagedListDialog.pageText(0, 60));
        assertEquals("Página 3 de 3 · 51 a 60 de 60", PagedListDialog.pageText(2, 60));
    }
}
