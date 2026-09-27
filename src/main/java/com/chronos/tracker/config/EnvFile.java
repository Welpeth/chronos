package com.chronos.tracker.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor simples de arquivos {@code .env}: linhas {@code CHAVE=valor}, comentários com {@code #}
 * e valores opcionalmente entre aspas.
 */
public final class EnvFile {

    private EnvFile() {
    }

    public static Map<String, String> read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Map.of();
        }
        return parse(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    public static Map<String, String> parse(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = unquote(line.substring(eq + 1).strip());
            values.put(key, value);
        }
        return values;
    }

    /**
     * Grava os valores no arquivo mantendo comentários e a ordem das linhas: uma chave que já existe tem a
     * linha trocada, uma que só aparece comentada ({@code # CHAVE=...}) é descomentada no lugar, e as demais
     * vão para o fim.
     */
    public static void write(Path path, Map<String, String> updates) throws IOException {
        List<String> lines = Files.exists(path)
                ? new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8))
                : new ArrayList<>();
        Map<String, String> pending = new LinkedHashMap<>(updates);
        for (int i = 0; i < lines.size() && !pending.isEmpty(); i++) {
            String key = keyOf(lines.get(i), false);
            if (key != null && pending.containsKey(key)) {
                lines.set(i, key + "=" + quote(pending.remove(key)));
            }
        }
        for (int i = 0; i < lines.size() && !pending.isEmpty(); i++) {
            String key = keyOf(lines.get(i), true);
            if (key != null && pending.containsKey(key)) {
                lines.set(i, key + "=" + quote(pending.remove(key)));
            }
        }
        pending.forEach((key, value) -> lines.add(key + "=" + quote(value)));

        Path absolute = path.toAbsolutePath();
        Path temp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Chave de uma linha {@code CHAVE=valor}; com {@code commented}, só de linhas {@code # CHAVE=valor}. */
    private static String keyOf(String raw, boolean commented) {
        String line = raw.strip();
        if (commented) {
            if (!line.startsWith("#")) {
                return null;
            }
            line = line.substring(1).strip();
        } else if (line.startsWith("#")) {
            return null;
        }
        if (line.startsWith("export ")) {
            line = line.substring("export ".length()).strip();
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String key = line.substring(0, eq).strip();
        return key.matches("[A-Za-z_][A-Za-z0-9_]*") ? key : null;
    }

    private static String quote(String value) {
        String clean = value == null ? "" : value.replace("\r", "").replace("\n", " ");
        boolean needsQuotes = clean.contains("#") || clean.contains(" ") || clean.contains("\"")
                || clean.contains("'");
        if (!needsQuotes) {
            return clean;
        }
        return clean.contains("\"") ? "'" + clean + "'" : "\"" + clean + "\"";
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
