package com.chronos.tracker.tracking;

import com.chronos.tracker.jira.JiraException;
import com.chronos.tracker.jira.JiraService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Comentários das tasks: o template padrão (um arquivo Markdown na pasta do Chronos), o que falta comentar nas
 * colunas monitoradas e o envio ao Jira. Todos os métodos que falam com o Jira bloqueiam.
 */
public final class CommentBook {

    /** Situação do comentário de uma task nas colunas monitoradas. */
    public enum Status {
        /** Ainda sem comentário: é pendência. */
        PENDING,
        /** Só o template foi posto; conta como feito, mas dá para completar. */
        TEMPLATE,
        /** Comentário escrito e salvo. */
        COMMENTED
    }

    /** Uma task das colunas monitoradas e o comentário dela, se já tem. */
    public record Item(String key, String summary, Status status, Optional<TaskComment> comment) {
    }

    private final Path templateFile;
    private final HistoryStore store;
    private final Supplier<JiraService> jira;
    private final Clock clock;

    public CommentBook(Path templateFile, HistoryStore store, Supplier<JiraService> jira, Clock clock) {
        this.templateFile = templateFile;
        this.store = store;
        this.jira = jira;
        this.clock = clock;
    }

    /** O template padrão, em Markdown; vazio se ainda não foi escrito. */
    public String template() {
        try {
            return Files.exists(templateFile) ? Files.readString(templateFile, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    public void saveTemplate(String markdown) throws IOException {
        Files.createDirectories(templateFile.toAbsolutePath().getParent());
        Files.writeString(templateFile, markdown == null ? "" : markdown, StandardCharsets.UTF_8);
    }

    /** Comentários já feitos, do mais recente para o mais antigo. */
    public List<TaskComment> history() throws HistoryStore.HistoryException {
        return store.comments();
    }

    /**
     * As tasks do usuário que estão nas colunas monitoradas, com a situação do comentário: primeiro as
     * pendentes, depois as só com template, depois as comentadas.
     */
    public List<Item> items(List<TaskView> live) throws HistoryStore.HistoryException {
        Map<String, TaskComment> byKey = new HashMap<>();
        store.comments().forEach(comment -> byKey.putIfAbsent(comment.issueKey(), comment));
        List<Item> items = new ArrayList<>();
        for (TaskView task : live) {
            if (!task.mine() || !task.inWorkingColumn()) {
                continue;
            }
            Optional<TaskComment> comment = Optional.ofNullable(byKey.get(task.key()));
            Status status = comment.map(c -> c.kind() == TaskComment.Kind.TEMPLATE ? Status.TEMPLATE : Status.COMMENTED)
                    .orElse(Status.PENDING);
            items.add(new Item(task.key(), task.summary(), status, comment));
        }
        items.sort(java.util.Comparator.comparing(Item::status));
        return items;
    }

    /**
     * Salva o comentário da task no Jira: troca o que o Chronos já tinha feito nela ou cria um novo. Passa a contar
     * como comentado (não mais só template).
     */
    public TaskComment save(String issueKey, String summary, String markdown) throws JiraException,
            HistoryStore.HistoryException {
        Optional<TaskComment> existing = find(issueKey);
        JiraService service = jira.get();
        String id;
        if (existing.isPresent() && !existing.get().commentId().isEmpty()) {
            id = existing.get().commentId();
            service.updateComment(issueKey, id, markdown);
        } else {
            id = service.addComment(issueKey, markdown);
        }
        TaskComment saved = new TaskComment(issueKey, summary, id, markdown, TaskComment.Kind.COMMENT, clock.instant());
        store.saveComment(saved);
        return saved;
    }

    /**
     * Põe o template na task, se ela ainda não tem comentário do Chronos e o template não está vazio. Devolve o
     * comentário criado.
     */
    public Optional<TaskComment> addTemplate(String issueKey, String summary) throws JiraException,
            HistoryStore.HistoryException {
        String template = template();
        if (template.isBlank() || find(issueKey).isPresent()) {
            return Optional.empty();
        }
        String id = jira.get().addComment(issueKey, template);
        TaskComment saved = new TaskComment(issueKey, summary, id, template, TaskComment.Kind.TEMPLATE, clock.instant());
        store.saveComment(saved);
        return Optional.of(saved);
    }

    private Optional<TaskComment> find(String issueKey) throws HistoryStore.HistoryException {
        return store.comments().stream().filter(c -> c.issueKey().equals(issueKey)).findFirst();
    }
}
