package com.chronos.tracker.tracking;

import java.time.Instant;

/**
 * O comentário que o Chronos fez numa task do Jira: o texto em Markdown, o id dele no Jira e se ainda é só o
 * template ou já foi escrito pela pessoa.
 */
public record TaskComment(String issueKey, String summary, String commentId, String body, Kind kind,
                          Instant updatedAt) {

    public enum Kind {
        /** O template padrão, posto sozinho quando a task entrou na coluna. Conta como feito, mas dá para completar. */
        TEMPLATE,
        /** Escrito (ou completado) e salvo pela pessoa. */
        COMMENT;

        public static Kind fromCode(String code) {
            return "template".equals(code) ? TEMPLATE : COMMENT;
        }

        public String code() {
            return this == TEMPLATE ? "template" : "comment";
        }
    }
}
