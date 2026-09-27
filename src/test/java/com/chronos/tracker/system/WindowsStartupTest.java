package com.chronos.tracker.system;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsStartupTest {

    @Test
    void registryCommandQuotesTheExecutableAndOpensMinimized() {
        String command = WindowsStartup.command(Path.of("/Programas/Chronos/Chronos.exe"));
        assertTrue(command.startsWith("\""), command);
        assertTrue(command.endsWith("Chronos.exe\" --background"), command);
    }
}
