package com.chronos.tracker.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppVersionTest {

    @Test
    void comparesNumberByNumber() {
        assertTrue(AppVersion.isNewer("0.10.0", "0.9.3"));
        assertTrue(AppVersion.isNewer("v0.3.0", "0.2.0"));
        assertTrue(AppVersion.isNewer("1.0", "0.9.9"));
        assertFalse(AppVersion.isNewer("0.2.0", "0.2.0"));
        assertFalse(AppVersion.isNewer("0.2", "0.2.0"));
        assertFalse(AppVersion.isNewer("0.1.9", "0.2.0"));
    }

    @Test
    void anyReleaseIsNewerThanADevBuild() {
        assertTrue(AppVersion.isNewer("0.2.0", "dev"));
        assertFalse(AppVersion.isNewer("sem-versao", "0.2.0"));
    }

    @Test
    void currentVersionComesFromThePom() {
        assertTrue(AppVersion.current().matches("\\d+\\.\\d+\\.\\d+"), AppVersion.current());
        assertEquals(AppVersion.current(), AppVersion.current().strip());
    }
}
