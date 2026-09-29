package com.chronos.tracker.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nTest {

    /** {@code t("...")} com o texto em português direto no código (não junta pedaços com +). */
    private static final Pattern CALL = Pattern.compile("\\bt\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*[,)]");

    @AfterEach
    void backToPortuguese() {
        I18n.use(I18n.Language.PT);
    }

    @Test
    void translatesAndFillsTheBlanks() {
        I18n.use(I18n.Language.EN);
        assertEquals("Save", I18n.t("Salvar"));
        assertEquals("Texto sem tradução", I18n.t("Texto sem tradução"));

        I18n.use(I18n.Language.PT);
        assertEquals("Salvar", I18n.t("Salvar"));
        assertEquals("Versão 0.3.0", I18n.t("Versão {0}", "0.3.0"));
    }

    @Test
    void unknownCodeFallsBackToPortuguese() {
        assertEquals(I18n.Language.EN, I18n.Language.fromCode("en-US"));
        assertEquals(I18n.Language.ES, I18n.Language.fromCode(" ES "));
        assertEquals(I18n.Language.PT, I18n.Language.fromCode("fr"));
        assertEquals(I18n.Language.PT, I18n.Language.fromCode(""));
    }

    @Test
    void everyTextInTheCodeHasEnglishAndSpanish() throws IOException {
        Set<String> texts = textsInCode();
        assertTrue(texts.size() > 100, "poucos textos encontrados: " + texts.size());
        for (I18n.Language language : new I18n.Language[] {I18n.Language.EN, I18n.Language.ES}) {
            Map<String, String> table = I18n.load(language);
            Set<String> missing = new TreeSet<>(texts);
            missing.removeAll(table.keySet());
            assertTrue(missing.isEmpty(), "Sem tradução em " + language.code + ":\n" + String.join("\n", missing));
            // Os {0}, {1}... do português têm que aparecer na tradução.
            for (String text : texts) {
                Matcher blanks = Pattern.compile("\\{\\d}").matcher(text);
                while (blanks.find()) {
                    assertTrue(table.get(text).contains(blanks.group()),
                            language.code + " perdeu " + blanks.group() + " em: " + text);
                }
            }
        }
    }

    private static Set<String> textsInCode() throws IOException {
        Set<String> texts = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher call = CALL.matcher(Files.readString(file));
                while (call.find()) {
                    texts.add(unescapeJava(call.group(1)));
                }
            }
        }
        return texts;
    }

    private static String unescapeJava(String literal) {
        return literal.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
