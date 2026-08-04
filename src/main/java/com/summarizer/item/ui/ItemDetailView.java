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
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Route("item")
@PageTitle("Item — Summarizer Studio")
@PermitAll
public class ItemDetailView extends VerticalLayout implements HasUrlParameter<Long> {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final ItemRepository items;
    private final CategoryRepository categories;
    private final CurrentUser currentUser;
    private final com.summarizer.item.TagService tagService;
    private final com.summarizer.item.pipeline.IngestPipeline pipeline;
    private final com.summarizer.ai.EmbeddingService embeddingService;

    private final com.summarizer.task.TaskService taskService;
    private final com.summarizer.task.TaskRepository taskRepository;

    public ItemDetailView(ItemRepository items, CategoryRepository categories, CurrentUser currentUser,
                          com.summarizer.item.TagService tagService,
                          com.summarizer.item.pipeline.IngestPipeline pipeline,
                          com.summarizer.ai.EmbeddingService embeddingService,
                          com.summarizer.task.TaskService taskService,
                          com.summarizer.task.TaskRepository taskRepository) {
        this.items = items;
        this.categories = categories;
        this.currentUser = currentUser;
        this.tagService = tagService;
        this.pipeline = pipeline;
        this.embeddingService = embeddingService;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        setPadding(true);
        addClassName("fade-in");
    }

    @Override
    public void setParameter(BeforeEvent event, Long itemId) {
        removeAll();
        Item item = items.findByIdAndUserId(itemId, currentUser.id()).orElse(null);
        if (item == null) {
            add(new Paragraph(getTranslation("detail.notFound")));
            return;
        }
        render(item);
    }

    private void render(Item item) {
        add(new H2(item.getTitle() == null || item.getTitle().isBlank()
                ? getTranslation("detail.noTitle") : item.getTitle()));

        HorizontalLayout meta = new HorizontalLayout();
        meta.setAlignItems(Alignment.CENTER);
        Button star = new Button(item.isFavorite()
                ? getTranslation("detail.favorite.on") : getTranslation("detail.favorite.off"));
        star.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        star.getStyle().set("color", item.isFavorite() ? "#f9a825" : "inherit");
        star.addClickListener(e -> {
            item.setFavorite(!item.isFavorite());
            items.save(item);
            star.setText(item.isFavorite()
                    ? getTranslation("detail.favorite.on") : getTranslation("detail.favorite.off"));
            star.getStyle().set("color", item.isFavorite() ? "#f9a825" : "inherit");
        });
        meta.add(star);
        meta.add(DashboardView.typeBadge(this, item.getType().name()));
        meta.add(new Span(getTranslation("detail.created", DATE_FORMAT.format(item.getCreatedAt()))));
        Span status = new Span(getTranslation("detail.status", item.getStatus().name()));
        status.getStyle().set("font-size", "0.85em");
        meta.add(status);
        add(meta);

        // Fehlgeschlagene Verarbeitung: Grund + Retry
        if (item.getStatus() == Item.Status.FAILED) {
            Div error = new Div();
            error.getStyle().set("background", "#ffebee").set("color", "#b71c1c")
                    .set("border-radius", "8px").set("padding", "0.6em 1em").set("max-width", "800px");
            error.add(new Span(getTranslation("detail.processingFailed")
                    + (item.getErrorMessage() != null ? ": " + item.getErrorMessage() : "")));
            Button retry = new Button(getTranslation("detail.retry"), e -> {
                pipeline.process(item.getId());
                Notification.show(getTranslation("detail.retryStarted"));
                UI.getCurrent().getPage().reload();
            });
            retry.addThemeVariants(ButtonVariant.LUMO_SMALL);
            retry.getStyle().set("margin-left", "1em");
            error.add(retry);
            add(error);
        }

        if (item.getSourceUrl() != null) {
            Anchor link = new Anchor(item.getSourceUrl(), item.getSourceUrl());
            link.setTarget("_blank");
            add(link);
        }
        if (item.getSnapshotPath() != null) {
            Anchor snapshot = new Anchor("files/" + item.getId() + "/snapshot",
                    getTranslation("detail.downloadSnapshot"));
            add(snapshot);
        }
        if (item.getType() == Item.Type.FILE && item.getFilePath() != null) {
            Anchor download = new Anchor("files/" + item.getId(),
                    getTranslation("detail.downloadOriginal"));
            add(download);
            // PDF: Browser rendern nativ — Inline-Viewer.
            // Word/PowerPoint können Browser nicht darstellen -> Textvorschau unten + Download.
            if (item.getFilePath().toLowerCase().endsWith(".pdf")) {
                com.vaadin.flow.component.Html viewer = new com.vaadin.flow.component.Html(
                        "<iframe src=\"files/" + item.getId()
                                + "\" style=\"width:100%;max-width:900px;height:600px;"
                                + "border:1px solid var(--vaadin-border-color,#ddd);border-radius:8px\"></iframe>");
                add(viewer);
            }
        }
        if (item.getType() == Item.Type.AUDIO && item.getFilePath() != null) {
            com.vaadin.flow.component.Html player = new com.vaadin.flow.component.Html(
                    "<audio controls src=\"files/" + item.getId()
                            + "\" style=\"width:100%;max-width:520px\"></audio>");
            add(player);
        }

        // Auto-Zusammenfassung prominent
        if (item.getSummary() != null && !item.getSummary().isBlank()) {
            Div summary = new Div();
            summary.setText(item.getSummary());
            summary.getStyle().set("background", "var(--vaadin-contrast-5pct, #f5f5f5)")
                    .set("border-left", "4px solid var(--vaadin-primary-color, #1a73e8)")
                    .set("border-radius", "6px").set("padding", "0.7em 1em")
                    .set("max-width", "800px").set("font-style", "italic");
            add(summary);
        }

        addCategoryEditor(item);
        addTagEditor(item);
        addTaskEditor(item);

        if (item.getType() == Item.Type.IMAGE && item.getFilePath() != null
                && Files.exists(Path.of(item.getFilePath()))) {
            Image image = new Image("/files/" + item.getId(), getTranslation("detail.imageAlt"));
            image.getStyle().set("max-width", "600px").set("max-height", "500px")
                    .set("border-radius", "8px");
            add(image);
        }

        if (item.getRawText() != null && !item.getRawText().isBlank()) {
            Div text = new Div();
            String raw = item.getRawText();
            text.setText(raw.length() > 5000 ? raw.substring(0, 5000) + " …" : raw);
            text.getStyle()
                    .set("white-space", "pre-wrap")
                    .set("max-width", "800px")
                    .set("max-height", "420px")
                    .set("overflow-y", "auto")
                    .set("border", "1px solid var(--vaadin-border-color, #ddd)")
                    .set("border-radius", "8px")
                    .set("padding", "0.8em 1em")
                    .set("font-size", "0.9em");
            add(text);
        }

        Button edit = new Button(getTranslation("detail.editButton"), e -> openEditDialog(item));
        Button delete = new Button(getTranslation("detail.delete"), e -> confirmDelete(item));
        delete.addThemeVariants(ButtonVariant.LUMO_ERROR);
        add(new HorizontalLayout(edit, delete));
    }

    private void addCategoryEditor(Item item) {
        List<Category> all = categories.findByUserIdOrderBySortOrderAscNameAsc(currentUser.id());
        ComboBox<Category> categoryBox = new ComboBox<>(getTranslation("detail.category"));
        categoryBox.setItems(all);
        categoryBox.setItemLabelGenerator(Category::getName);
        categoryBox.setClearButtonVisible(true);
        all.stream().filter(c -> c.getId().equals(item.getCategoryId())).findFirst()
                .ifPresent(categoryBox::setValue);
        categoryBox.addValueChangeListener(e -> {
            if (!e.isFromClient()) {
                return;
            }
            item.setCategoryId(e.getValue() == null ? null : e.getValue().getId());
            item.setCategoryConfidence(e.getValue() == null ? null : 1.0f);   // manuell = sicher
            items.save(item);
            Notification.show(getTranslation("detail.categoryUpdated"));
        });

        HorizontalLayout row = new HorizontalLayout(categoryBox);
        if (item.getCategoryConfidence() != null && item.getCategoryConfidence() < 1.0f) {
            Span confidence = new Span(getTranslation("detail.confidence",
                    Math.round(item.getCategoryConfidence() * 100)));
            confidence.getStyle().set("font-size", "0.85em").set("align-self", "end")
                    .set("color", "var(--vaadin-text-color-secondary, #666)");
            row.add(confidence);
        }
        add(row);
    }

    private void addTagEditor(Item item) {
        com.vaadin.flow.component.textfield.TextField tags =
                new com.vaadin.flow.component.textfield.TextField(getTranslation("detail.tags.label"));
        tags.setWidth("420px");
        tags.setValue(String.join(", ", tagService.tagsForItem(item.getId())));
        tags.setPlaceholder(getTranslation("detail.tags.placeholder"));
        Button save = new Button(getTranslation("detail.tags.save"), e -> {
            tagService.setTags(currentUser.id(), item.getId(),
                    java.util.Arrays.asList(tags.getValue().split(",")));
            Notification.show(getTranslation("detail.tags.saved"));
        });
        save.addThemeVariants(ButtonVariant.LUMO_SMALL);
        HorizontalLayout row = new HorizontalLayout(tags, save);
        row.setAlignItems(Alignment.END);
        add(row);
    }

    /** Aufgaben: zugeordnete anzeigen (Chip mit ✕), bestehende wählen oder neue anlegen. */
    private void addTaskEditor(Item item) {
        HorizontalLayout row = new HorizontalLayout();
        row.setAlignItems(Alignment.CENTER);
        row.getStyle().set("flex-wrap", "wrap").set("gap", "0.4em");
        Span label = new Span(getTranslation("detail.tasks.title") + ":");
        label.getStyle().set("font-weight", "600").set("font-size", "0.9em");
        row.add(label);
        for (com.summarizer.task.Task task : taskService.tasksForItem(item.getId())) {
            Span chip = new Span(task.getTitle() + " ✕");
            chip.getStyle().set("background", "var(--s-accent-soft, #eef)")
                    .set("color", "var(--s-accent, #3b4bd8)").set("border-radius", "999px")
                    .set("padding", "0.15em 0.8em").set("font-size", "0.82em")
                    .set("cursor", "pointer");
            chip.getElement().setProperty("title", getTranslation("detail.tasks.removeHint"));
            chip.getElement().addEventListener("click", e -> {
                taskService.unlinkItem(item.getId(), task.getId());
                chip.setVisible(false);
                Notification.show(getTranslation("detail.tasks.removed"));
            });
            row.add(chip);
        }
        Button assign = new Button(getTranslation("detail.tasks.assign"),
                e -> openAssignTaskDialog(item));
        assign.addThemeVariants(ButtonVariant.LUMO_SMALL);
        row.add(assign);
        add(row);
    }

    private void openAssignTaskDialog(Item item) {
        com.vaadin.flow.component.dialog.Dialog dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(getTranslation("detail.tasks.assignHeader"));
        dialog.setWidth("420px");

        ComboBox<com.summarizer.task.Task> existing = new ComboBox<>(getTranslation("detail.tasks.existing"));
        existing.setItems(taskRepository.findByUserIdAndStatusNotOrderByDueDateAscIdAsc(
                currentUser.id(), com.summarizer.task.Task.Status.DONE));
        existing.setItemLabelGenerator(com.summarizer.task.Task::getTitle);
        existing.setWidthFull();

        com.vaadin.flow.component.textfield.TextField newTitle =
                new com.vaadin.flow.component.textfield.TextField(getTranslation("detail.tasks.newTitle"));
        newTitle.setWidthFull();

        Button save = new Button(getTranslation("detail.tasks.create"), e -> {
            com.summarizer.task.Task target = existing.getValue();
            if (target == null && !newTitle.getValue().isBlank()) {
                target = new com.summarizer.task.Task(currentUser.id(), newTitle.getValue().strip());
                target.setStartDate(java.time.LocalDate.now());
                target.setDueDate(java.time.LocalDate.now().plusDays(7));
                target = taskRepository.save(target);
            }
            if (target == null) {
                return;
            }
            taskService.linkItem(item.getId(), target.getId());
            dialog.close();
            Notification.show(getTranslation("detail.tasks.assigned"));
            removeAll();
            render(item);
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        dialog.add(new com.vaadin.flow.component.orderedlayout.VerticalLayout(existing, newTitle));
        dialog.getFooter().add(save);
        dialog.open();
    }

    /** Titel, Zusammenfassung und Inhalt/Beschreibung manuell anpassen; Suche wird aktualisiert. */
    private void openEditDialog(Item item) {
        com.vaadin.flow.component.dialog.Dialog dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(getTranslation("detail.edit.title"));
        dialog.setWidth("720px");

        com.vaadin.flow.component.textfield.TextField title =
                new com.vaadin.flow.component.textfield.TextField(getTranslation("detail.edit.titleField"));
        title.setWidthFull();
        title.setValue(item.getTitle() == null ? "" : item.getTitle());

        com.vaadin.flow.component.textfield.TextArea summary =
                new com.vaadin.flow.component.textfield.TextArea(getTranslation("detail.edit.summaryField"));
        summary.setWidthFull();
        summary.setHeight("90px");
        summary.setValue(item.getSummary() == null ? "" : item.getSummary());

        com.vaadin.flow.component.textfield.TextArea text =
                new com.vaadin.flow.component.textfield.TextArea(getTranslation("detail.edit.textField"));
        text.setWidthFull();
        text.setHeight("260px");
        text.setValue(item.getRawText() == null ? "" : item.getRawText());

        Button save = new Button(getTranslation("detail.edit.save"), e -> {
            item.setTitle(title.getValue().isBlank() ? null : title.getValue().strip());
            item.setSummary(summary.getValue().isBlank() ? null : summary.getValue().strip());
            item.setRawText(text.getValue().isBlank() ? null : text.getValue());
            items.save(item);
            // Vektoren an den neuen Text anpassen, damit die Suche stimmt
            embeddingService.embedAndStore(item.getId(),
                    (item.getTitle() == null ? "" : item.getTitle() + "\n")
                    + (item.getSummary() == null ? "" : item.getSummary() + "\n")
                    + (item.getRawText() == null ? "" : item.getRawText()));
            dialog.close();
            Notification.show(getTranslation("detail.edit.saved"));
            UI.getCurrent().getPage().reload();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        dialog.add(new com.vaadin.flow.component.orderedlayout.VerticalLayout(title, summary, text, save));
        dialog.open();
    }

    private void confirmDelete(Item item) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(getTranslation("detail.deleteConfirm.header"));
        String title = item.getTitle() == null ? getTranslation("detail.noTitle") : item.getTitle();
        dialog.setText(getTranslation(item.getFilePath() != null
                ? "detail.deleteConfirm.textWithFile" : "detail.deleteConfirm.text", title));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("detail.deleteConfirm.cancel"));
        dialog.setConfirmText(getTranslation("detail.delete"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            if (item.getFilePath() != null) {
                try {
                    Files.deleteIfExists(Path.of(item.getFilePath()));
                } catch (Exception ignored) {
                }
            }
            items.delete(item);   // item_embeddings via ON DELETE CASCADE
            Notification.show(getTranslation("detail.deleted"));
            UI.getCurrent().navigate(DashboardView.class);
        });
        dialog.open();
    }
}
