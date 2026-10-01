package com.chronos.tracker.ui;

import java.awt.Desktop;
import java.net.URI;

/** Links para as tasks no Jira, abertos no navegador padrão. */
final class JiraLinks {

    private JiraLinks() {
    }

    /** O endereço da task, como o Jira mostra: {@code https://empresa.atlassian.net/browse/RP-500}. */
    static String browse(String baseUrl, String issueKey) {
        return baseUrl.strip().replaceAll("/+$", "") + "/browse/" + issueKey;
    }

    /** Abre a task no navegador, fora da thread da UI. Sem navegador disponível, não faz nada. */
    static void open(String baseUrl, String issueKey) {
        String url = browse(baseUrl, issueKey);
        Thread thread = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                }
            } catch (Exception e) {
                // Sem navegador: o endereço continua na dica do link.
            }
        }, "chronos-open-link");
        thread.setDaemon(true);
        thread.start();
    }
}
