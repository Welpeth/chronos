package com.chronos.tracker.update;

import com.chronos.tracker.config.AppVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.StreamSupport;

/** Procura no GitHub a última release do Chronos e baixa o instalador dela. */
public final class Updater {

    public static final String RELEASES = "https://api.github.com/repos/Welpeth/chronos/releases/latest";

    /** Release publicada: versão, notas e o endereço do instalador (.exe). */
    public record Release(String version, String notes, String installerUrl, String installerName) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String latestUrl;

    public Updater() {
        this(RELEASES);
    }

    Updater(String latestUrl) {
        this.latestUrl = latestUrl;
    }

    /** A última release, se for mais nova que {@code installed} e tiver instalador. */
    public Optional<Release> newerThan(String installed) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(latestUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Chronos/" + installed)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("O GitHub respondeu HTTP " + response.statusCode() + " ao procurar atualização");
        }
        return parse(mapper.readTree(response.body())).filter(release -> AppVersion.isNewer(release.version(), installed));
    }

    static Optional<Release> parse(JsonNode json) {
        String version = json.path("tag_name").asText("").replaceFirst("^[vV]", "");
        return StreamSupport.stream(json.path("assets").spliterator(), false)
                .filter(asset -> asset.path("name").asText("").toLowerCase().endsWith(".exe"))
                .findFirst()
                .filter(asset -> !version.isEmpty())
                .map(asset -> new Release(version, json.path("body").asText(""),
                        asset.path("browser_download_url").asText(), asset.path("name").asText()));
    }

    /** Baixa o instalador para {@code dir} e devolve o arquivo. */
    public Path download(Release release, Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path target = dir.resolve(release.installerName());
        HttpRequest request = HttpRequest.newBuilder(URI.create(release.installerUrl()))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Chronos")
                .GET()
                .build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(target);
            throw new IOException("O download do instalador falhou (HTTP " + response.statusCode() + ")");
        }
        return target;
    }
}
