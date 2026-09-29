package com.chronos.tracker.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Textos da interface no idioma escolhido. O texto em português é a própria chave: {@code t("Salvar")} devolve
 * "Save" em inglês. As traduções ficam em {@code i18n/<idioma>.tsv} (português, TAB, tradução); o que faltar
 * aparece em português.
 *
 * <p>Partes variáveis vão como {@code {0}}, {@code {1}}: {@code t("Versão {0}", "0.3.0")}.
 */
public final class I18n {

    public enum Language {
        PT("pt", "Português", Locale.forLanguageTag("pt-BR")),
        EN("en", "English", Locale.forLanguageTag("en-US")),
        ES("es", "Español", Locale.forLanguageTag("es-ES"));

        public final String code;
        public final String label;
        public final Locale locale;

        Language(String code, String label, Locale locale) {
            this.code = code;
            this.label = label;
            this.locale = locale;
        }

        /** Pelo código gravado no .env ({@code pt}, {@code en}, {@code es}); português se não reconhecer. */
        public static Language fromCode(String code) {
            String normalized = code == null ? "" : code.strip().toLowerCase(Locale.ROOT);
            for (Language language : values()) {
                if (normalized.startsWith(language.code)) {
                    return language;
                }
            }
            return PT;
        }
    }

    private static volatile Language current = Language.PT;
    private static volatile Map<String, String> table = Map.of();

    private I18n() {
    }

    public static void use(Language language) {
        table = language == Language.PT ? Map.of() : load(language);
        current = language;
    }

    public static Language language() {
        return current;
    }

    public static Locale locale() {
        return current.locale;
    }

    public static String t(String portuguese) {
        return table.getOrDefault(portuguese, portuguese);
    }

    public static String t(String portuguese, Object... args) {
        String text = t(portuguese);
        for (int i = 0; i < args.length; i++) {
            text = text.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return text;
    }

    /** Traduções de um idioma, lidas do recurso; vazio se não existir. */
    public static Map<String, String> load(Language language) {
        return read("/com/chronos/tracker/i18n/" + language.code + ".tsv").orElse(Map.of());
    }

    private static Optional<Map<String, String>> read(String resource) {
        try (InputStream in = I18n.class.getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            Map<String, String> entries = new HashMap<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    entries.put(unescape(line.substring(0, tab)), unescape(line.substring(tab + 1)));
                }
            }
            return Optional.of(entries);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** No arquivo, quebra de linha é escrita como {@code \n}. */
    static String unescape(String text) {
        return text.replace("\\n", "\n").replace("\\t", "\t");
    }
}
