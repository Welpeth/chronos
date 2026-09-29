package com.chronos.tracker.jira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdfTest {

    @Test
    void templateWithHeadingListEmojiAndMarks() {
        JsonNode doc = Adf.fromMarkdown("""
                # Validação
                :light_bulb_on: **O que foi feito**
                - Ajustei o _login_
                - Ver `config.yml` e [docs](https://ex.com)

                1. Abrir
                2. Testar""");

        assertEquals("doc", doc.path("type").asText());
        JsonNode content = doc.path("content");
        assertEquals("heading", content.get(0).path("type").asText());
        assertEquals(1, content.get(0).path("attrs").path("level").asInt());

        JsonNode para = content.get(1).path("content");
        assertEquals("emoji", para.get(0).path("type").asText());
        assertEquals(":light_bulb_on:", para.get(0).path("attrs").path("shortName").asText());
        assertEquals("O que foi feito", para.get(2).path("text").asText());
        assertEquals("strong", para.get(2).path("marks").get(0).path("type").asText());

        JsonNode bullets = content.get(2);
        assertEquals("bulletList", bullets.path("type").asText());
        assertEquals(2, bullets.path("content").size());
        JsonNode second = bullets.path("content").get(1).path("content").get(0).path("content");
        assertEquals("code", second.get(1).path("marks").get(0).path("type").asText());
        assertEquals("https://ex.com", second.get(3).path("marks").get(0).path("attrs").path("href").asText());

        assertEquals("orderedList", content.get(3).path("type").asText());
        assertEquals(4, content.size());
    }

    @Test
    void linesTogetherStayInOneParagraphWithBreaks() {
        JsonNode content = Adf.fromMarkdown("linha um\nlinha dois\n\noutro").path("content");
        assertEquals(2, content.size());
        assertEquals("hardBreak", content.get(0).path("content").get(1).path("type").asText());
        assertEquals("linha um\nlinha dois\noutro", Adf.plainText(Adf.fromMarkdown("linha um\nlinha dois\n\noutro")));
    }

    @Test
    void emptyTextIsAnEmptyParagraph() {
        assertEquals("paragraph", Adf.fromMarkdown("").path("content").get(0).path("type").asText());
    }

    @Test
    void snakeCaseWordsAreNotItalic() {
        JsonNode text = Adf.inline("use o campo my_field_name aqui");
        assertEquals(1, text.size());
    }
}
