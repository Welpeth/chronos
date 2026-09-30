package com.chronos.tracker.tracking;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Projeto do Jira de cada task, pela chave: "SCRUM-12" é do projeto SCRUM. Serve para separar na tela os quadros
 * que estão no mesmo Jira.
 */
public final class Projects {

    /** Sem projeto escolhido: mostra todos. */
    public static final String ALL = "";

    private Projects() {
    }

    /** "SCRUM-12" → "SCRUM". Chave sem hífen (digitada à mão) fica sem projeto (""). */
    public static String of(String issueKey) {
        if (issueKey == null) {
            return "";
        }
        int dash = issueKey.lastIndexOf('-');
        return dash <= 0 ? "" : issueKey.substring(0, dash).toUpperCase();
    }

    /** Se a task entra no projeto escolhido; {@link #ALL} aceita todas. */
    public static boolean matches(String issueKey, String project) {
        return project == null || project.isEmpty() || of(issueKey).equalsIgnoreCase(project);
    }

    /** Se algum grupo da task (quadro ou projeto) está entre os escolhidos; sem escolhidos, aceita todas. */
    public static boolean matches(Collection<String> groups, Set<String> selected) {
        if (selected == null || selected.isEmpty()) {
            return true;
        }
        return groups.stream().anyMatch(group -> selected.stream().anyMatch(group::equalsIgnoreCase));
    }

    /** Projetos distintos das chaves, em ordem alfabética, sem o vazio. */
    public static List<String> distinct(Collection<String> issueKeys) {
        TreeSet<String> projects = new TreeSet<>();
        issueKeys.stream().map(Projects::of).filter(Objects::nonNull).filter(p -> !p.isEmpty()).forEach(projects::add);
        return List.copyOf(projects);
    }
}
