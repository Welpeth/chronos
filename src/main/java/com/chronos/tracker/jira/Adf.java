package com.chronos.tracker.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converte o texto do comentário, escrito em Markdown, para o formato de documento do Jira (ADF), que é o que a
 * API de comentários aceita. Cobre o que se usa num comentário: títulos ({@code #}), listas ({@code -}, {@code 1.}),
 * negrito, itálico, código, links e emojis do Jira escritos por extenso, como {@code :light_bulb_on:}.
 */
public final class Adf {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$");
    /** Negrito, itálico, código, link e emoji, na ordem em que aparecem na linha. */
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)]\\(([^)\\s]+)\\)"
                    + "|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)"
                    + "|:([a-z0-9_+\\-]+):");

    private Adf() {
    }

    /** Documento ADF do texto em Markdown. Texto vazio vira um parágrafo vazio. */
    public static ObjectNode fromMarkdown(String markdown) {
        ObjectNode doc = JSON.objectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        ArrayNode content = doc.putArray("content");

        List<String> paragraph = new ArrayList<>();
        ArrayNode list = null;
        String listType = null;
        for (String line : (markdown == null ? "" : markdown).replace("\r", "").split("\n", -1)) {
            Matcher heading = HEADING.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            Matcher ordered = ORDERED.matcher(line);
            boolean isRule = RULE.matcher(line).matches();
            String itemType = !isRule && bullet.matches() ? "bulletList" : ordered.matches() ? "orderedList" : null;
            if (itemType == null || !itemType.equals(listType)) {
                list = null;
                listType = null;
            }
            if (line.isBlank() || heading.matches() || itemType != null || isRule) {
                flushParagraph(content, paragraph);
            }
            if (isRule) {
                content.addObject().put("type", "rule");
            } else if (heading.matches()) {
                ObjectNode node = content.addObject();
                node.put("type", "heading");
                node.putObject("attrs").put("level", heading.group(1).length());
                node.set("content", inline(heading.group(2).strip()));
            } else if (itemType != null) {
                if (list == null) {
                    ObjectNode node = content.addObject();
                    node.put("type", itemType);
                    list = node.putArray("content");
                    listType = itemType;
                }
                String text = itemType.equals("bulletList") ? bullet.group(1) : ordered.group(1);
                ObjectNode item = list.addObject();
                item.put("type", "listItem");
                ObjectNode para = item.putArray("content").addObject();
                para.put("type", "paragraph");
                para.set("content", inline(text));
            } else if (!line.isBlank()) {
                paragraph.add(line);
            }
        }
        flushParagraph(content, paragraph);
        if (content.isEmpty()) {
            content.addObject().put("type", "paragraph");
        }
        return doc;
    }

    /** Linhas seguidas viram um parágrafo, com quebra de linha entre elas. */
    private static void flushParagraph(ArrayNode content, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        ObjectNode para = content.addObject();
        para.put("type", "paragraph");
        ArrayNode nodes = para.putArray("content");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                nodes.addObject().put("type", "hardBreak");
            }
            nodes.addAll(inline(lines.get(i)));
        }
        lines.clear();
    }

    static ArrayNode inline(String text) {
        ArrayNode nodes = JSON.arrayNode();
        Matcher m = INLINE.matcher(text);
        int at = 0;
        while (m.find()) {
            addText(nodes, text.substring(at, m.start()), null);
            if (m.group(1) != null || m.group(2) != null) {
                addText(nodes, m.group(1) != null ? m.group(1) : m.group(2), mark("strong"));
            } else if (m.group(3) != null) {
                addText(nodes, m.group(3), mark("code"));
            } else if (m.group(4) != null) {
                ObjectNode link = mark("link");
                link.putObject("attrs").put("href", m.group(5));
                addText(nodes, m.group(4), link);
            } else if (m.group(6) != null || m.group(7) != null) {
                addText(nodes, m.group(6) != null ? m.group(6) : m.group(7), mark("em"));
            } else {
                ObjectNode emoji = nodes.addObject();
                emoji.put("type", "emoji");
                emoji.putObject("attrs").put("shortName", ":" + m.group(8) + ":");
            }
            at = m.end();
        }
        addText(nodes, text.substring(at), null);
        return nodes;
    }

    private static ObjectNode mark(String type) {
        ObjectNode mark = JSON.objectNode();
        mark.put("type", type);
        return mark;
    }

    private static void addText(ArrayNode nodes, String text, ObjectNode mark) {
        if (text.isEmpty()) {
            return;
        }
        ObjectNode node = nodes.addObject();
        node.put("type", "text");
        node.put("text", text);
        if (mark != null) {
            node.putArray("marks").add(mark);
        }
    }

    /** Texto simples de um documento ADF (para mostrar um comentário que veio do Jira). */
    public static String plainText(JsonNode node) {
        StringBuilder out = new StringBuilder();
        collect(node, out);
        return out.toString().strip();
    }

    private static void collect(JsonNode node, StringBuilder out) {
        String type = node.path("type").asText("");
        switch (type) {
            case "text" -> out.append(node.path("text").asText(""));
            case "emoji" -> out.append(node.path("attrs").path("shortName").asText(""));
            case "hardBreak" -> out.append('\n');
            default -> {
                node.path("content").forEach(child -> collect(child, out));
                if (type.equals("paragraph") || type.equals("heading") || type.equals("listItem")) {
                    out.append('\n');
                }
            }
        }
    }
}
