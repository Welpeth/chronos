package com.chronos.tracker.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private String release(String tag) {
        return """
                {"tag_name":"%s","body":"Notas","assets":[
                  {"name":"Chronos-0.3.0-portatil.zip","browser_download_url":"%s/zip"},
                  {"name":"Chronos-0.3.0.exe","browser_download_url":"%s/exe"}]}""".formatted(tag, base, base);
    }

    @Test
    void findsTheInstallerOfANewerRelease() throws Exception {
        serve("/latest", 200, release("v0.3.0"));

        Optional<Updater.Release> found = new Updater(base + "/latest").newerThan("0.2.0");

        assertEquals(Optional.of(new Updater.Release("0.3.0", "Notas", base + "/exe", "Chronos-0.3.0.exe")), found);
    }

    @Test
    void sameOrOlderReleaseMeansNoUpdate() throws Exception {
        serve("/latest", 200, release("v0.2.0"));
        assertTrue(new Updater(base + "/latest").newerThan("0.2.0").isEmpty());
        assertTrue(new Updater(base + "/latest").newerThan("0.10.0").isEmpty());
    }

    @Test
    void noReleaseYetMeansNoUpdate() throws Exception {
        serve("/latest", 404, "{}");
        assertTrue(new Updater(base + "/latest").newerThan("0.2.0").isEmpty());
    }

    @Test
    void downloadsTheInstaller(@TempDir Path dir) throws Exception {
        serve("/exe", 200, "instalador");
        Updater.Release release = new Updater.Release("0.3.0", "", base + "/exe", "Chronos-0.3.0.exe");

        Path installer = new Updater(base + "/latest").download(release, dir.resolve("updates"));

        assertEquals("instalador", Files.readString(installer));
        assertEquals("Chronos-0.3.0.exe", installer.getFileName().toString());
    }
}
