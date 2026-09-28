package com.chronos.tracker.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPathsTest {

    @Test
    void runningFromMavenUsesTheCurrentFolder() {
        assertEquals(Optional.empty(), AppPaths.installedDataDir(null, "C:\\Users\\h\\AppData\\Roaming", "/home/h"));
    }

    @Test
    void theInstalledAppKeepsItsDataInAppData() {
        assertEquals(Optional.of(Path.of("/appdata", "Chronos")),
                AppPaths.installedDataDir("C:\\Chronos\\Chronos.exe", "/appdata", "/home/h"));
        assertEquals(Optional.of(Path.of("/home/h", ".config", "Chronos")),
                AppPaths.installedDataDir("/opt/chronos/bin/Chronos", null, "/home/h"));
    }
}
