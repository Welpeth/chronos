package com.chronos.tracker.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JiraLinksTest {

    @Test
    void browseUrlIgnoresTrailingSlashes() {
        assertEquals("https://valesoft.atlassian.net/browse/RP-500",
                JiraLinks.browse("https://valesoft.atlassian.net/", "RP-500"));
        assertEquals("https://valesoft.atlassian.net/browse/RP-1",
                JiraLinks.browse(" https://valesoft.atlassian.net ", "RP-1"));
    }
}
