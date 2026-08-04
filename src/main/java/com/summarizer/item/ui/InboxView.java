package com.summarizer.item.ui;

import com.summarizer.base.CurrentUser;
import com.summarizer.category.Category;
import com.summarizer.category.CategoryRepository;
import com.summarizer.item.Item;
import com.summarizer.item.ItemRepository;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Inbox: Items ohne Kategorie oder mit LLM-Konfidenz < 0.5 —
 * zur schnellen manuellen Nachsortierung.
 */
@Route("inbox")
@PageTitle("Unsortiert — Summarizer Studio")
@PermitAll
public class InboxView extends VerticalLayout {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final ItemRepository items;
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final List<Category> categories;
    private final Grid<Item> grid = new Grid<>();

    public InboxView(ItemRepository items, CategoryRepository categoryRepository,
                     JdbcTemplate jdbc, CurrentUser currentUser) {
        this.items = items;
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.categories = categoryRepository.findByUserIdOrderBySortOrderAscNameAsc(currentUser.id());
        setPadding(true);
        addClassName("fade-in");
        setSizeFull();   // volle Fensterhöhe nutzen, Grid füllt den Rest

        add(new H2(getTranslation("inbox.title")));
        add(new Paragraph(getTranslation("inbox.description")));

        grid.addComponentColumn(this::statusBadge)
                .setHeader(getTranslation("inbox.column.status")).setAutoWidth(true).setFlexGrow(0);
        Grid.Column<Item> titleColumn = grid
                .addColumn(i -> i.getTitle() == null || i.getTitle().isBlank()
                        ? getTranslation("inbox.noTitle") : i.getTitle())
                .setHeader(getTranslation("inbox.column.title")).setFlexGrow(1);
        grid.addColumn(i -> i.getType().name())
                .setHeader(getTranslation("inbox.column.type")).setAutoWidth(true);
        grid.addColumn(i -> DATE_FORMAT.format(i.getCreatedAt()))
                .setHeader(getTranslation("inbox.column.created")).setAutoWidth(true);
        grid.addColumn(i -> i.getCategoryConfidence() == null ? "—"
                        : "%.0f%%".formatted(i.getCategoryConfidence() * 100))
                .setHeader(getTranslation("inbox.column.confidence")).setAutoWidth(true);
        grid.addComponentColumn(this::assignmentControls)
                .setHeader(getTranslation("inbox.column.assign")).setAutoWidth(true);
        grid.addItemClickListener(e -> {
            if (e.getColumn() != null && e.getColumn().equals(titleColumn)) {
                UI.getCurrent().navigate(ItemDetailView.class, e.getItem().getId());
            }
        });
        grid.setSizeFull();
        add(grid);
        expand(grid);
        refresh();
    }

    /** Pipeline-Status: wartet / läuft / fehlgeschlagen / fertig-unsortiert. */
    private com.vaadin.flow.component.html.Span statusBadge(Item item) {
        String text;
        String color;
        switch (item.getStatus()) {
            case PENDING -> { text = getTranslation("inbox.status.PENDING"); color = "#757575"; }
            case PROCESSING -> { text = getTranslation("inbox.status.PROCESSING"); color = "#1a73e8"; }
            case FAILED -> { text = getTranslation("inbox.status.FAILED"); color = "#c62828"; }
            default -> { text = getTranslation("inbox.status.UNSORTED"); color = "#8d6e63"; }
        }
        com.vaadin.flow.component.html.Span badge = new com.vaadin.flow.component.html.Span(text);
        badge.getStyle().set("background", color).set("color", "white")
                .set("border-radius", "999px").set("padding", "0.15em 0.8em")
                .set("font-size", "0.78em").set("font-weight", "600")
                .set("white-space", "nowrap");
        if (item.getStatus() == Item.Status.FAILED && item.getErrorMessage() != null) {
            badge.getElement().setProperty("title", item.getErrorMessage());
        }
        return badge;
    }

    private HorizontalLayout assignmentControls(Item item) {
        ComboBox<Category> box = new ComboBox<>();
        box.setItems(categories);
        box.setItemLabelGenerator(Category::getName);
        box.setPlaceholder(getTranslation("inbox.categoryPlaceholder"));
        box.setWidth("180px");
        categories.stream().filter(c -> c.getId().equals(item.getCategoryId())).findFirst()
                .ifPresent(box::setValue);
        Button save = new Button(getTranslation("inbox.assignButton"), e -> {
            if (box.getValue() == null) {
                return;
            }
            item.setCategoryId(box.getValue().getId());
            item.setCategoryConfidence(1.0f);
            items.save(item);
            Notification.show(getTranslation("inbox.assigned", box.getValue().getName()));
            refresh();
        });
        save.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
        return new HorizontalLayout(box, save);
    }

    private void refresh() {
        // Auch alles, was gerade in der Pipeline steckt (Telegram, Chrome, Import)
        // oder fehlgeschlagen ist — so ist der Verarbeitungsstand hier sichtbar.
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM items
                WHERE user_id = ? AND (category_id IS NULL OR category_confidence < 0.5
                       OR status IN ('PENDING', 'PROCESSING', 'FAILED'))
                ORDER BY (status IN ('PENDING', 'PROCESSING')) DESC, created_at DESC
                LIMIT 200
                """, Long.class, currentUser.id());
        grid.setItems(items.findAllById(ids));
    }

    /** Solange Pipeline-Items da sind: bei Änderungen per Push aktualisieren. */
    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        UI ui = attachEvent.getUI();
        Thread.ofVirtual().start(() -> {
            String lastSignature = "";
            try {
                while (true) {
                    Thread.sleep(4000);
                    String signature = jdbc.queryForObject("""
                            SELECT coalesce(string_agg(id || ':' || status, ',' ORDER BY id), '')
                            FROM items WHERE user_id = ? AND status IN ('PENDING', 'PROCESSING', 'FAILED')
                            """, String.class, currentUser.id());
                    if (signature != null && !signature.equals(lastSignature)) {
                        lastSignature = signature;
                        ui.access(this::refresh);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // UI geschlossen — Wächter beenden
            }
        });
    }
}
