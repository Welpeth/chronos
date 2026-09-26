package com.chronos.tracker.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
