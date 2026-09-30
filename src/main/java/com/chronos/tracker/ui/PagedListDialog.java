package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Function;

/**
 * Janela com a lista completa do painel (tarefas ou atividade recente), {@value #PAGE_SIZE} itens por página.
 * Fica aberta enquanto o painel continua atualizando: {@link #update(List)} troca os itens e mantém a página.
 */
public final class PagedListDialog<T> {

    static final int PAGE_SIZE = 25;

    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final Function<T, Node> rowFactory;
    private final String emptyText;
    private final VBox rows = new VBox(0);
    private final Label pageLabel = new Label();
    private final Button previous = new Button(I18n.t("Anterior"));
    private final Button next = new Button(I18n.t("Próxima"));
    private final ScrollPane scroll = new ScrollPane(rows);
    private List<T> items = List.of();
    private int page;

    public PagedListDialog(Window owner, String icon, String title, String emptyText, Function<T, Node> rowFactory) {
        this.rowFactory = rowFactory;
        this.emptyText = emptyText;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(title);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dialog-title");
        Button close = new Button();
        close.setGraphic(Icons.of(Icons.CLOSE, 22, "icon-white"));
        close.getStyleClass().add("dialog-close");
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(14, Icons.of(icon, 30, "icon-white"), titleLabel, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("dialog-header");
        ManualEntryDialog.makeDraggable(header, stage);

        rows.getStyleClass().add("paged-rows");
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(460);
        scroll.getStyleClass().add("paged-scroll");
        VBox body = new VBox(scroll);
        body.getStyleClass().add("dialog-body");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        previous.getStyleClass().add("dialog-secondary");
        previous.setOnAction(e -> turnTo(page - 1));
        next.getStyleClass().add("dialog-secondary");
        next.setOnAction(e -> turnTo(page + 1));
        pageLabel.getStyleClass().add("paged-label");
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(14, pageLabel, footerSpacer, previous, next);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("dialog-footer");

        VBox card = new VBox(header, body, footer);
        card.getStyleClass().add("manual-dialog");
        card.setPrefWidth(620);
        StackPane root = new StackPane(card);
        root.getStyleClass().add("dialog-shadow-area");

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.setScene(scene);
        stage.setOnShown(e -> {
            if (owner != null) {
                stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
                stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
            }
        });
    }

    public void show(List<T> items) {
        this.items = List.copyOf(items);
        showPage(0);
        stage.show();
    }

    public void hide() {
        stage.hide();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    /** Troca os itens sem sair da página atual (ou vai para a última, se a lista encolheu). */
    public void update(List<T> items) {
        this.items = List.copyOf(items);
        double position = scroll.getVvalue();
        showPage(page);
        scroll.setVvalue(position);
    }

    private void turnTo(int requested) {
        showPage(requested);
        scroll.setVvalue(0);
    }

    private void showPage(int requested) {
        int pages = pageCount(items.size());
        page = Math.max(0, Math.min(requested, pages - 1));
        rows.getChildren().clear();
        if (items.isEmpty()) {
            Label empty = new Label(emptyText);
            empty.getStyleClass().add("muted");
            rows.getChildren().add(empty);
        }
        int from = page * PAGE_SIZE;
        items.subList(from, Math.min(from + PAGE_SIZE, items.size()))
                .forEach(item -> rows.getChildren().add(rowFactory.apply(item)));
        pageLabel.setText(pageText(page, items.size()));
        previous.setDisable(page == 0);
        next.setDisable(page >= pages - 1);
    }

    static int pageCount(int size) {
        return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** Ex.: "Página 2 de 3 · 26 a 50 de 60". */
    static String pageText(int page, int size) {
        if (size == 0) {
            return I18n.t("Página 1 de 1");
        }
        int from = page * PAGE_SIZE + 1;
        int to = Math.min(from + PAGE_SIZE - 1, size);
        return I18n.t("Página {0} de {1} · {2} a {3} de {4}", page + 1, pageCount(size), from, to, size);
    }
}
