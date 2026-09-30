package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Chips para escolher quais quadros (ou projetos) aparecem: "Todos" e um por quadro. Dá para marcar vários; sem
 * nenhum marcado, vale "Todos". Some quando só existe um grupo.
 */
public final class BoardChips {

    private final FlowPane root = new FlowPane(8, 8);
    private final Consumer<Set<String>> onChange;
    private List<String> options = List.of();
    private Set<String> selected = Set.of();
    private boolean byBoard;

    public BoardChips(Consumer<Set<String>> onChange) {
        this.onChange = onChange;
        root.setAlignment(Pos.CENTER_LEFT);
        root.getStyleClass().add("board-chips");
        show(false);
    }

    public Node getView() {
        return root;
    }

    /** Redesenha só quando os grupos ou a escolha mudam, para não perder o clique no meio de uma atualização. */
    public void update(List<String> groups, Set<String> chosen, boolean boards) {
        if (groups.equals(options) && chosen.equals(selected) && boards == byBoard) {
            return;
        }
        options = List.copyOf(groups);
        selected = Set.copyOf(chosen);
        byBoard = boards;
        root.getChildren().clear();
        ToggleButton all = chip(boards ? I18n.t("Todos os quadros") : I18n.t("Todos os projetos"), selected.isEmpty());
        all.setOnAction(e -> onChange.accept(Set.of()));
        root.getChildren().add(all);
        for (String group : options) {
            ToggleButton chip = chip(boards ? group : I18n.t("Projeto {0}", group), selected.contains(group));
            chip.setOnAction(e -> {
                Set<String> next = new LinkedHashSet<>(selected);
                if (!next.remove(group)) {
                    next.add(group);
                }
                // Marcar todos é o mesmo que "Todos".
                onChange.accept(next.containsAll(options) ? Set.of() : next);
            });
            root.getChildren().add(chip);
        }
        show(options.size() > 1);
    }

    private static ToggleButton chip(String text, boolean on) {
        ToggleButton chip = new ToggleButton(text);
        chip.getStyleClass().add("filter-chip");
        chip.setSelected(on);
        return chip;
    }

    private void show(boolean visible) {
        root.setVisible(visible);
        root.setManaged(visible);
    }

    /** Grupos escolhidos que ainda existem; os que sumiram (outro quadro, outro Jira) saem da escolha. */
    static Set<String> stillPresent(Set<String> chosen, List<String> groups) {
        if (groups.isEmpty()) {
            return chosen;
        }
        List<String> kept = new ArrayList<>(chosen);
        kept.retainAll(groups);
        return Set.copyOf(kept);
    }
}
