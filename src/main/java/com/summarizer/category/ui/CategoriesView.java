package com.summarizer.category.ui;

import com.summarizer.base.CurrentUser;
import com.summarizer.category.Category;
import com.summarizer.category.CategoryRepository;
import com.summarizer.category.CategoryTreeService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.dnd.GridDropLocation;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Kategorien-Verwaltung als Baum: anlegen (auch als Unterkategorie),
 * bearbeiten, löschen, per Drag&Drop verschieben.
 * Drop AUF eine Kategorie = wird Unterkategorie; Drop ZWISCHEN zwei = gleiche
 * Ebene an dieser Position.
 */
@Route("categories")
@PageTitle("Kategorien — Summarizer Studio")
@PermitAll
public class CategoriesView extends VerticalLayout {

    private final CategoryRepository repository;
    private final CategoryTreeService treeService;
    private final CurrentUser currentUser;
    private final com.summarizer.item.pipeline.IngestPipeline pipeline;
    private final com.summarizer.item.ItemRepository itemRepository;
    private final com.summarizer.base.JobProgressService jobs;
    private final TreeGrid<Category> tree = new TreeGrid<>();
    private Category dragged;

    public CategoriesView(CategoryRepository repository, CategoryTreeService treeService,
                          CurrentUser currentUser,
                          com.summarizer.item.pipeline.IngestPipeline pipeline,
                          com.summarizer.item.ItemRepository itemRepository,
                          com.summarizer.base.JobProgressService jobs) {
        this.itemRepository = itemRepository;
        this.repository = repository;
        this.treeService = treeService;
        this.currentUser = currentUser;
        this.pipeline = pipeline;
        this.jobs = jobs;
        setPadding(true);
        addClassName("fade-in");
        setSizeFull();

        add(new H2(getTranslation("categories.title")));
        add(new Paragraph(getTranslation("categories.intro")));

        Button create = new Button(getTranslation("categories.create"), e -> openEditor(null, null));
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button reclassify = new Button(getTranslation("categories.reclassify"), e -> confirmReclassify());
        reclassify.setTooltipText(getTranslation("categories.reclassify.tooltip"));

        add(new HorizontalLayout(create, reclassify));
        add(new com.summarizer.base.ui.JobProgressBar(jobs,
                com.summarizer.base.JobProgressService.reclassifyKey(currentUser.id())));

        buildTree();
        add(tree);
        setFlexGrow(1, tree);
        refresh();
    }

    private void buildTree() {
        tree.addComponentHierarchyColumn(category -> {
            Span dot = new Span("● ");
            dot.getStyle().set("color", category.getColor() == null || category.getColor().isBlank()
                    ? "#78909c" : category.getColor());
            return new Span(dot, new Span(category.getName()));
        }).setHeader(getTranslation("categories.column.name")).setAutoWidth(true).setFlexGrow(0);
        tree.addColumn(Category::getDescription)
                .setHeader(getTranslation("categories.column.description")).setFlexGrow(1);
        tree.addComponentColumn(category -> {
            Button child = new Button(getTranslation("categories.addChild"), e -> openEditor(null, category));
            child.addThemeVariants(ButtonVariant.LUMO_SMALL);
            child.setTooltipText(getTranslation("categories.addChild.tooltip"));
            Button edit = new Button(getTranslation("categories.edit"), e -> openEditor(category, null));
            edit.addThemeVariants(ButtonVariant.LUMO_SMALL);
            Button delete = new Button(getTranslation("categories.delete"), e -> confirmDelete(category));
            delete.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
            return new HorizontalLayout(child, edit, delete);
        }).setHeader(getTranslation("categories.column.actions")).setAutoWidth(true).setFlexGrow(0);

        tree.setRowsDraggable(true);
        tree.setDropMode(GridDropMode.ON_TOP_OR_BETWEEN);
        tree.addDragStartListener(e -> dragged = e.getDraggedItems().getFirst());
        tree.addDragEndListener(e -> dragged = null);
        tree.addDropListener(e -> {
            if (dragged == null) {
                return;
            }
            if (dragged.isFavorites()) {
                Notification.show(getTranslation("categories.favoritesTopLevel"));
                return;
            }
            Category target = e.getDropTargetItem().orElse(null);
            GridDropLocation location = e.getDropLocation();
            if (target != null && target.getId().equals(dragged.getId())) {
                return;
            }
            try {
                if (target == null) {
                    moveToParentEnd(dragged, null);
                } else if (location == GridDropLocation.ON_TOP) {
                    if (treeService.wouldCreateCycle(currentUser.id(), dragged, target)) {
                        Notification.show(getTranslation("categories.dropCycle"));
                        return;
                    }
                    moveToParentEnd(dragged, target);
                } else {
                    placeRelative(dragged, target, location == GridDropLocation.ABOVE);
                }
                refresh();
            } catch (IllegalStateException ex) {
                Notification.show(ex.getMessage());
            }
        });
    }

    /** Als (letztes) Kind von parent einhängen; parent null = oberste Ebene. */
    private void moveToParentEnd(Category category, Category parent) {
        category.setParentId(parent == null ? null : parent.getId());
        category.setSortOrder(siblingsOf(parent == null ? null : parent.getId(), category).size());
        repository.save(category);
    }

    /** Auf gleiche Ebene wie target, direkt davor/danach. */
    private void placeRelative(Category category, Category target, boolean above) {
        Long newParentId = target.getParentId();
        if (newParentId != null) {
            Category newParent = repository.findById(newParentId).orElse(null);
            if (newParent != null && treeService.wouldCreateCycle(currentUser.id(), category, newParent)) {
                throw new IllegalStateException(getTranslation("categories.placeCycle"));
            }
        }
        List<Category> siblings = siblingsOf(newParentId, category);
        int index = 0;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(target.getId())) {
                index = above ? i : i + 1;
                break;
            }
        }
        category.setParentId(newParentId);
        siblings.add(index, category);
        for (int i = 0; i < siblings.size(); i++) {
            siblings.get(i).setSortOrder(i);
        }
        repository.saveAll(siblings);
    }

    private List<Category> siblingsOf(Long parentId, Category exclude) {
        List<Category> siblings = new ArrayList<>(repository
                .findByUserIdOrderBySortOrderAscNameAsc(currentUser.id()).stream()
                .filter(c -> Objects.equals(c.getParentId(), parentId))
                .filter(c -> !c.getId().equals(exclude.getId()))
                .toList());
        return siblings;
    }

    private void openEditor(Category existing, Category presetParent) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(existing == null
                ? (presetParent == null
                        ? getTranslation("categories.dialog.new")
                        : getTranslation("categories.dialog.newChild", presetParent.getName()))
                : getTranslation("categories.dialog.edit"));

        TextField name = new TextField(getTranslation("categories.field.name"));
        name.setWidthFull();
        TextArea description = new TextArea(getTranslation("categories.field.description"));
        description.setWidthFull();
        description.setPlaceholder(getTranslation("categories.field.description.placeholder"));
        // Nativer Farbwähler + Vorschlagspalette, statt CSS-Code tippen zu müssen
        com.vaadin.flow.component.html.Input colorPicker = new com.vaadin.flow.component.html.Input();
        colorPicker.setType("color");
        colorPicker.getStyle().set("width", "56px").set("height", "38px")
                .set("border", "1px solid var(--vaadin-border-color, #ccc)")
                .set("border-radius", "8px").set("padding", "2px").set("cursor", "pointer");
        TextField color = new TextField();
        color.setPlaceholder("#1a73e8");
        color.setWidth("140px");
        colorPicker.addValueChangeListener(e -> color.setValue(e.getValue()));
        color.addValueChangeListener(e -> {
            if (e.getValue() != null && e.getValue().matches("#[0-9a-fA-F]{6}")) {
                colorPicker.setValue(e.getValue());
            }
        });

        Div swatches = new Div();
        swatches.getStyle().set("display", "flex").set("gap", "0.3em").set("flex-wrap", "wrap");
        for (String preset : new String[]{"#1a73e8", "#e91e63", "#2e7d32", "#f9a825",
                "#7b1fa2", "#00838f", "#ef6c00", "#455a64"}) {
            Div swatch = new Div();
            swatch.getStyle().set("width", "26px").set("height", "26px")
                    .set("border-radius", "50%").set("background", preset)
                    .set("cursor", "pointer").set("border", "2px solid transparent");
            swatch.getElement().addEventListener("click", e -> {
                color.setValue(preset);
                colorPicker.setValue(preset);
            });
            swatches.add(swatch);
        }

        HorizontalLayout colorRow = new HorizontalLayout(colorPicker, color, swatches);
        colorRow.setAlignItems(Alignment.CENTER);
        colorRow.getStyle().set("flex-wrap", "wrap");
        com.vaadin.flow.component.html.Span colorLabel =
                new com.vaadin.flow.component.html.Span(getTranslation("categories.field.color"));
        colorLabel.getStyle().set("font-size", "0.85em").set("font-weight", "500");
        IntegerField sortOrder = new IntegerField(getTranslation("categories.field.sortOrder"));
        sortOrder.setValue(0);

        ComboBox<Category> parent = new ComboBox<>(getTranslation("categories.field.parent"));
        parent.setItems(repository.findByUserIdOrderBySortOrderAscNameAsc(currentUser.id()).stream()
                .filter(c -> existing == null || !c.getId().equals(existing.getId()))
                .toList());
        parent.setItemLabelGenerator(Category::getName);
        parent.setClearButtonVisible(true);
        parent.setWidthFull();
        if (presetParent != null) {
            parent.setValue(presetParent);
        }

        if (existing != null) {
            name.setValue(existing.getName());
            description.setValue(existing.getDescription() == null ? "" : existing.getDescription());
            color.setValue(existing.getColor() == null ? "" : existing.getColor());
            if (existing.getColor() != null && existing.getColor().matches("#[0-9a-fA-F]{6}")) {
                colorPicker.setValue(existing.getColor());
            }
            sortOrder.setValue(existing.getSortOrder());
            if (existing.getParentId() != null) {
                repository.findById(existing.getParentId()).ifPresent(parent::setValue);
            }
        }

        Button save = new Button(getTranslation("categories.saveButton"), e -> {
            String newName = name.getValue().strip();
            if (newName.isBlank()) {
                name.setInvalid(true);
                name.setErrorMessage(getTranslation("categories.nameEmpty"));
                return;
            }
            // Namen müssen eindeutig sein (Groß-/Kleinschreibung egal)
            boolean duplicate = repository.findByUserIdOrderBySortOrderAscNameAsc(currentUser.id())
                    .stream()
                    .filter(c -> existing == null || !c.getId().equals(existing.getId()))
                    .anyMatch(c -> c.getName().equalsIgnoreCase(newName));
            if (duplicate) {
                name.setInvalid(true);
                name.setErrorMessage(getTranslation("categories.nameDuplicate"));
                Notification.show(getTranslation("categories.nameExists", newName));
                return;
            }
            Category category = existing == null
                    ? new Category(currentUser.id(), newName, description.getValue())
                    : existing;
            if (existing != null && treeService.wouldCreateCycle(currentUser.id(), existing, parent.getValue())) {
                Notification.show(getTranslation("categories.cycleInvalid"));
                return;
            }
            category.setName(newName);
            category.setDescription(description.getValue());
            category.setColor(color.getValue().isBlank() ? null : color.getValue().trim());
            category.setSortOrder(sortOrder.getValue() == null ? 0 : sortOrder.getValue());
            category.setParentId(parent.getValue() == null ? null : parent.getValue().getId());
            repository.save(category);
            dialog.close();
            refresh();
            Notification.show(getTranslation("categories.saved"));
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        dialog.setWidth("560px");
        VerticalLayout form = new VerticalLayout(name, description, parent,
                colorLabel, colorRow, sortOrder);
        form.setPadding(false);
        form.setSpacing(false);
        form.getStyle().set("gap", "0.6em");
        dialog.add(form);

        Button cancel = new Button(getTranslation("categories.cancel"), e -> dialog.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        HorizontalLayout footer = new HorizontalLayout(cancel, save);
        footer.setWidthFull();
        footer.setJustifyContentMode(JustifyContentMode.END);
        dialog.getFooter().add(footer);
        dialog.open();
    }

    private void confirmReclassify() {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(getTranslation("categories.reclassify.header"));
        dialog.setText(getTranslation("categories.reclassify.text"));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("categories.cancel"));
        dialog.setConfirmText(getTranslation("categories.reclassify.confirm"));
        dialog.addConfirmListener(e -> {
            pipeline.reclassifyAll(currentUser.id());
            Notification.show(getTranslation("categories.reclassify.started"));
        });
        dialog.open();
    }

    private void confirmDelete(Category category) {
        // Alle Lösch-Blockaden einheitlich: Mitte, 6 Sekunden
        if (category.isFavorites()) {
            Notification.show(getTranslation("categories.deleteFavorites"), 6000,
                    Notification.Position.MIDDLE);
            return;
        }
        if (category.isDefaultCategory()) {
            Notification.show(getTranslation("categories.deleteDefault", category.getName()), 6000,
                    Notification.Position.MIDDLE);
            return;
        }
        // Nur leere Kategorien loeschbar — inklusive aller Unterkategorien
        List<Long> subtree = treeService.selfAndDescendantIds(currentUser.id(), category.getId());
        long used = itemRepository.countByCategoryIdIn(subtree);
        if (used > 0) {
            Notification.show(getTranslation("categories.deleteNotEmpty",
                    category.getName(), String.valueOf(used)), 6000,
                    Notification.Position.MIDDLE);
            return;
        }
        boolean hasChildren = !treeService.children(currentUser.id(), category).isEmpty();
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(getTranslation("categories.delete.header"));
        dialog.setText(getTranslation("categories.delete.text", category.getName())
                + (hasChildren ? " " + getTranslation("categories.delete.textChildren") : ""));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("categories.cancel"));
        dialog.setConfirmText(getTranslation("categories.delete"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            repository.delete(category);   // items.category_id → NULL, categories.parent_id → NULL
            refresh();
            Notification.show(getTranslation("categories.deleted"));
        });
        dialog.open();
    }

    private void refresh() {
        List<Category> roots = treeService.roots(currentUser.id());
        tree.setItems(roots, parent -> treeService.children(currentUser.id(), parent));
        tree.expandRecursively(roots, 5);
    }
}
