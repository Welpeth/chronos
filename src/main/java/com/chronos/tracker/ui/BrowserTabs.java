package com.chronos.tracker.ui;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Abas no topo da página, no estilo das abas do navegador, mas sem botão de fechar. */
public final class BrowserTabs {

    private BrowserTabs() {
    }

    public static TabPane create() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("browser-tabs");
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return tabs;
    }

    /** Aba com o conteúdo rolando por dentro, para as abas ficarem sempre visíveis. */
    public static Tab tab(String title, Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("browser-tab-scroll");
        return new Tab(title, scroll);
    }
}
