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
import java.util.Map;
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

    private final com.summarizer.ai.LlmRouter llm;

    public CategoriesView(CategoryRepository repository, CategoryTreeService treeService,
                          CurrentUser currentUser,
                          com.summarizer.item.pipeline.IngestPipeline pipeline,
                          com.summarizer.item.ItemRepository itemRepository,
                          com.summarizer.base.JobProgressService jobs,
                          com.summarizer.ai.LlmRouter llm) {
        this.itemRepository = itemRepository;
        this.repository = repository;
        this.treeService = treeService;
        this.currentUser = currentUser;
        this.pipeline = pipeline;
        this.jobs = jobs;
        this.llm = llm;
        setPadding(true);
        addClassName("fade-in");
        setSizeFull();

        add(new H2(getTranslation("categories.title")));
        add(new Paragraph(getTranslation("categories.intro")));

        Button create = new Button(getTranslation("categories.create"), e -> openEditor(null, null));
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button reclassify = new Button(getTranslation("categories.reclassify"), e -> confirmReclassify());
        reclassify.setTooltipText(getTranslation("categories.reclassify.tooltip"));

        Button aiSuggest = new Button(getTranslation("categories.ai.button"), e -> openAiDialog(null));
        aiSuggest.setTooltipText(getTranslation("categories.ai.tooltip"));

        com.vaadin.flow.component.html.Anchor export = new com.vaadin.flow.component.html.Anchor(
                "export/categories.json", "");
        export.getElement().setAttribute("download", true);
        Button exportButton = new Button(getTranslation("categories.export"));
        exportButton.setTooltipText(getTranslation("categories.export.tooltip"));
        export.add(exportButton);

        Button importButton = new Button(getTranslation("categories.import"),
                e -> openImportDialog());
        importButton.setTooltipText(getTranslation("categories.import.tooltip"));

        HorizontalLayout actions = new HorizontalLayout(create, reclassify, aiSuggest,
                export, importButton);
        actions.setAlignItems(Alignment.CENTER);
        actions.getStyle().set("flex-wrap", "wrap");
        add(actions);
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
        }).setHeader(getTranslation("categories.column.name"))
                .setWidth("240px").setFlexGrow(0).setResizable(true);
        tree.addColumn(Category::getDescription)
                .setHeader(getTranslation("categories.column.description"))
                .setFlexGrow(1).setResizable(true);
        tree.addComponentColumn(category -> {
            Button child = new Button(getTranslation("categories.addChild"), e -> openEditor(null, category));
            child.addThemeVariants(ButtonVariant.LUMO_SMALL);
            child.setTooltipText(getTranslation("categories.addChild.tooltip"));
            Button edit = new Button(getTranslation("categories.edit"), e -> openEditor(category, null));
            edit.addThemeVariants(ButtonVariant.LUMO_SMALL);
            Button aiChildren = new Button("✨", e -> openAiDialog(category));
            aiChildren.addThemeVariants(ButtonVariant.LUMO_SMALL);
            aiChildren.setTooltipText(getTranslation("categories.ai.rowTooltip"));
            Button delete = new Button(getTranslation("categories.delete"), e -> confirmDelete(category));
            delete.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
            return new HorizontalLayout(child, edit, aiChildren, delete);
        }).setHeader(getTranslation("categories.column.actions"))
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);

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
        dialog.addConfirmListener(e -> openReclassifyOptions());
        dialog.open();
    }

    /** Optionen für den Lauf: Manuelles schützen, neue Kategorien erlauben. */
    private void openReclassifyOptions() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("categories.reclassify.options.title"));
        com.vaadin.flow.component.checkbox.Checkbox keepManual =
                new com.vaadin.flow.component.checkbox.Checkbox(
                        getTranslation("categories.reclassify.keepManual"), true);
        com.vaadin.flow.component.checkbox.Checkbox allowNew =
                new com.vaadin.flow.component.checkbox.Checkbox(
                        getTranslation("categories.reclassify.allowNew"), true);
        Paragraph hint = new Paragraph(getTranslation("categories.reclassify.options.hint"));
        hint.getStyle().set("font-size", "0.85em")
                .set("color", "var(--lumo-secondary-text-color)");
        Button start = new Button(getTranslation("categories.reclassify.start"), e -> {
            pipeline.reclassifyAll(currentUser.id(), keepManual.getValue(), allowNew.getValue());
            dialog.close();
            Notification.show(getTranslation("categories.reclassify.started"));
        });
        start.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(new VerticalLayout(keepManual, allowNew, hint));
        dialog.getFooter().add(start);
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
        // Geschützte Kategorien dürfen auch nicht als Unterkategorie mitgelöscht werden
        List<Category> subtreeCategories = new ArrayList<>();
        for (Long id : subtree) {
            repository.findById(id).ifPresent(subtreeCategories::add);
        }
        boolean protectedInside = subtreeCategories.stream()
                .anyMatch(c -> !c.getId().equals(category.getId())
                        && (c.isFavorites() || c.isDefaultCategory()));
        if (protectedInside) {
            Notification.show(getTranslation("categories.deleteProtectedChild"), 6000,
                    Notification.Position.MIDDLE);
            return;
        }

        boolean hasChildren = subtree.size() > 1;
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(getTranslation("categories.delete.header"));
        dialog.setText(getTranslation("categories.delete.text", category.getName())
                + (hasChildren
                        ? " " + getTranslation("categories.delete.textChildren",
                                String.valueOf(subtree.size() - 1))
                        : ""));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("categories.cancel"));
        dialog.setConfirmText(getTranslation("categories.delete"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            // Kompletten Teilbaum löschen — Unterkategorien verschwinden mit
            repository.deleteAllById(subtree);
            refresh();
            Notification.show(getTranslation("categories.deleted"));
        });
        dialog.open();
    }

    // ---------- Import: Kategorien-Baum aus JSON-Datei ----------

    /** JSON aus dem Export einlesen; nur nicht vorhandene Kategorien anlegen. */
    private void openImportDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("categories.import.dialogTitle"));
        dialog.add(new Paragraph(getTranslation("categories.import.dialogText")));

        com.vaadin.flow.component.upload.receivers.MemoryBuffer buffer =
                new com.vaadin.flow.component.upload.receivers.MemoryBuffer();
        com.vaadin.flow.component.upload.Upload upload =
                new com.vaadin.flow.component.upload.Upload(buffer);
        upload.setAcceptedFileTypes(".json", "application/json");
        upload.setMaxFiles(1);
        upload.addSucceededListener(e -> {
            try {
                var mapper = new tools.jackson.databind.ObjectMapper();
                List<Map<String, Object>> roots = mapper.readValue(buffer.getInputStream(),
                        mapper.getTypeFactory().constructCollectionType(
                                List.class, Map.class));
                int[] counts = new int[2];
                java.util.Map<String, Category> existing = new java.util.HashMap<>();
                for (Category c : repository.findByUserIdOrderBySortOrderAscNameAsc(currentUser.id())) {
                    existing.put(c.getName().toLowerCase(), c);
                }
                for (Map<String, Object> root : roots) {
                    importNode(root, null, existing, counts);
                }
                dialog.close();
                refresh();
                Notification.show(getTranslation("categories.ai.applied",
                        counts[0], counts[1]), 6000, Notification.Position.MIDDLE);
            } catch (Exception ex) {
                Notification.show(getTranslation("categories.import.failed", ex.getMessage()),
                        6000, Notification.Position.MIDDLE);
            }
        });
        dialog.add(upload);
        dialog.open();
    }

    @SuppressWarnings("unchecked")
    private void importNode(Map<String, Object> node, Long parentId,
                            java.util.Map<String, Category> existing, int[] counts) {
        Object nameValue = node.get("name");
        if (!(nameValue instanceof String name) || name.isBlank() || name.length() > 100) {
            return;
        }
        Category target = existing.get(name.strip().toLowerCase());
        if (target == null) {
            target = new Category(currentUser.id(), name.strip(),
                    node.get("description") instanceof String d ? d : "");
            if (node.get("color") instanceof String color && !color.isBlank()) {
                target.setColor(color);
            }
            target.setParentId(parentId);
            target = repository.save(target);
            existing.put(target.getName().toLowerCase(), target);
            counts[0]++;
        } else {
            counts[1]++;
        }
        if (node.get("children") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> childMap) {
                    importNode((Map<String, Object>) childMap, target.getId(), existing, counts);
                }
            }
        }
    }

    // ---------- KI-Assistent: Kategorien vorschlagen lassen ----------

    /** Bearbeitbarer Vorschlags-Knoten des KI-Baums. */
    private static final class Proposal {
        private String name;
        private String description;
        private final List<Proposal> children = new ArrayList<>();
        private Proposal parent;

        private Proposal(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    /**
     * Dialog: links Wunsch-Prompt ans LLM, rechts der bearbeitbare Vorschlagsbaum
     * (umbenennen, Beschreibung ändern, löschen). "Übernehmen" legt nur Kategorien
     * an, die noch nicht existieren. parent != null = Unterkategorien für diese Kategorie.
     */
    private void openAiDialog(Category parent) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(parent == null
                ? getTranslation("categories.ai.dialogTitle")
                : getTranslation("categories.ai.dialogTitleFor", parent.getName()));
        dialog.setWidth("980px");
        dialog.setHeight("620px");

        List<Proposal> roots = new ArrayList<>();
        TreeGrid<Proposal> proposalTree = new TreeGrid<>();

        TextArea prompt = new TextArea(getTranslation("categories.ai.promptLabel"));
        prompt.setWidthFull();
        prompt.setHeight("140px");
        prompt.setPlaceholder(parent == null
                ? getTranslation("categories.ai.promptPlaceholder")
                : getTranslation("categories.ai.promptPlaceholderFor", parent.getName()));

        Span status = new Span();
        status.getStyle().set("font-size", "0.85em")
                .set("color", "var(--lumo-secondary-text-color)");

        Button generate = new Button(getTranslation("categories.ai.generate"));
        generate.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        generate.addClickListener(e -> {
            if (prompt.getValue().isBlank()) {
                prompt.setInvalid(true);
                return;
            }
            generate.setEnabled(false);
            status.setText(getTranslation("categories.ai.thinking"));
            com.vaadin.flow.component.UI ui = com.vaadin.flow.component.UI.getCurrent();
            String userWish = prompt.getValue().strip();
            Thread.ofVirtual().start(() -> {
                String answer;
                try {
                    answer = llm.generate(buildCategoryPrompt(userWish, parent), PROPOSAL_SCHEMA);
                } catch (Exception ex) {
                    answer = null;
                }
                String result = answer;
                ui.access(() -> {
                    generate.setEnabled(true);
                    List<Proposal> parsed = parseProposalsJson(result);
                    if (parsed.isEmpty()) {
                        parsed = parseProposals(result);   // Fallback: Zeilenformat
                    }
                    if (parsed.isEmpty()) {
                        status.setText(getTranslation("categories.ai.empty"));
                        return;
                    }
                    roots.clear();
                    roots.addAll(parsed);
                    proposalTree.setItems(roots, p -> p.children);
                    proposalTree.expandRecursively(roots, 3);
                    status.setText(getTranslation("categories.ai.editHint"));
                });
            });
        });

        VerticalLayout left = new VerticalLayout(prompt, generate, status);
        left.setPadding(false);
        left.setWidth("340px");
        left.setFlexShrink(0);

        // Rechter Baum: Name und Beschreibung direkt editierbar, Zeile löschbar
        proposalTree.addComponentHierarchyColumn(node -> {
            TextField name = new TextField();
            name.setValue(node.name);
            name.setWidthFull();
            name.addValueChangeListener(e -> node.name = e.getValue());
            return name;
        }).setHeader(getTranslation("categories.ai.column.name")).setFlexGrow(2).setResizable(true);
        proposalTree.addComponentColumn(node -> {
            TextField description = new TextField();
            description.setValue(node.description == null ? "" : node.description);
            description.setWidthFull();
            description.addValueChangeListener(e -> node.description = e.getValue());
            return description;
        }).setHeader(getTranslation("categories.ai.column.description")).setFlexGrow(3).setResizable(true);
        proposalTree.addComponentColumn(node -> {
            Button remove = new Button("✕", e -> {
                if (node.parent == null) {
                    roots.remove(node);
                } else {
                    node.parent.children.remove(node);
                }
                proposalTree.setItems(roots, p -> p.children);
                proposalTree.expandRecursively(roots, 3);
            });
            remove.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR,
                    ButtonVariant.LUMO_TERTIARY);
            remove.setTooltipText(getTranslation("categories.ai.remove"));
            return remove;
        }).setWidth("70px").setFlexGrow(0);
        proposalTree.setSizeFull();

        HorizontalLayout body = new HorizontalLayout(left, proposalTree);
        body.setSizeFull();
        body.setFlexGrow(1, proposalTree);
        dialog.add(body);

        Button apply = new Button(getTranslation("categories.ai.apply"), e -> {
            if (roots.isEmpty()) {
                Notification.show(getTranslation("categories.ai.nothing"));
                return;
            }
            int[] counts = new int[2];   // [0] angelegt, [1] übersprungen
            java.util.Map<String, Category> existing = new java.util.HashMap<>();
            for (Category c : repository.findByUserIdOrderBySortOrderAscNameAsc(currentUser.id())) {
                existing.put(c.getName().toLowerCase(), c);
            }
            Long parentId = parent == null ? null : parent.getId();
            for (Proposal root : roots) {
                applyProposal(root, parentId, existing, counts);
            }
            dialog.close();
            refresh();
            Notification.show(getTranslation("categories.ai.applied",
                    counts[0], counts[1]), 6000, Notification.Position.MIDDLE);
        });
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button(getTranslation("categories.cancel"), e -> dialog.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        dialog.getFooter().add(new HorizontalLayout(cancel, apply));
        dialog.open();
    }

    private String buildCategoryPrompt(String wish, Category parent) {
        String context = parent == null ? "" :
                "Alle vorgeschlagenen Kategorien werden Unterkategorien von \""
                        + parent.getName() + "\""
                        + (parent.getDescription() == null || parent.getDescription().isBlank()
                                ? "" : " (" + parent.getDescription() + ")")
                        + ". Schlage NUR diese Unterkategorien vor, nicht die Oberkategorie selbst.\n";
        return """
                Erstelle eine Kategorien-Hierarchie für ein persönliches Wissensarchiv.
                Wunsch des Nutzers: %s
                %s
                Regeln:
                - Höchstens 3 Ebenen und insgesamt höchstens 15 Kategorien.
                - Jede Beschreibung ist EIN Satz: wofür die Kategorie ist, plus typische Schlagworte.
                - Format pro Zeile: so viele "-" wie die Tiefe (oberste Ebene ohne "-"),
                  dann Name|Beschreibung. Keine weiteren Zeichen, keine Erklärung.

                Beispiel:
                Fotografie|Kameras, Objektive, Bildbearbeitung und Fototechnik
                - Kameras|Kameramodelle, Hersteller, Kaufberatung
                - - Objektive|Brennweiten, Lichtstärke, Marken
                """.formatted(wish, context);
    }

    /** Structured-Output-Schema: bis zu 3 Ebenen Kategorien mit Beschreibung. */
    private static final Map<String, Object> PROPOSAL_SCHEMA = buildProposalSchema();

    private static Map<String, Object> buildProposalSchema() {
        Map<String, Object> leaf = Map.of("type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "description", Map.of("type", "string")),
                "required", List.of("name"));
        Map<String, Object> mid = Map.of("type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "description", Map.of("type", "string"),
                        "children", Map.of("type", "array", "items", leaf)),
                "required", List.of("name"));
        Map<String, Object> top = Map.of("type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "description", Map.of("type", "string"),
                        "children", Map.of("type", "array", "items", mid)),
                "required", List.of("name"));
        return Map.of("type", "object",
                "properties", Map.of("categories",
                        Map.of("type", "array", "maxItems", 8, "items", top)),
                "required", List.of("categories"));
    }

    /** JSON-Antwort (Structured Output) in den Vorschlagsbaum überführen. */
    private List<Proposal> parseProposalsJson(String answer) {
        List<Proposal> roots = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return roots;
        }
        try {
            var node = new tools.jackson.databind.ObjectMapper().readTree(answer);
            if (!node.has("categories")) {
                return roots;
            }
            for (var top : node.get("categories")) {
                Proposal proposal = toProposal(top, null);
                if (proposal != null) {
                    roots.add(proposal);
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        return roots;
    }

    private Proposal toProposal(tools.jackson.databind.JsonNode node, Proposal parent) {
        String name = node.path("name").asText("").strip();
        if (name.isBlank() || name.length() > 100) {
            return null;
        }
        Proposal proposal = new Proposal(name, node.path("description").asText(""));
        proposal.parent = parent;
        if (node.has("children")) {
            for (var child : node.get("children")) {
                Proposal childProposal = toProposal(child, proposal);
                if (childProposal != null) {
                    proposal.children.add(childProposal);
                }
            }
        }
        return proposal;
    }

    /** Zeilenformat "-- Name|Beschreibung" in einen Baum überführen. */
    private List<Proposal> parseProposals(String answer) {
        List<Proposal> roots = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return roots;
        }
        java.util.Deque<Proposal> stack = new java.util.ArrayDeque<>();
        for (String rawLine : answer.strip().lines().toList()) {
            String line = rawLine.strip();
            if (line.isEmpty() || !line.contains("|")) {
                continue;
            }
            // Tiefe = Anzahl fuehrender "-" (Leerzeichen dazwischen erlaubt)
            long dashes = line.replaceFirst("^([-\\s]*).*$", "$1")
                    .chars().filter(c -> c == '-').count();
            String content = line.replaceFirst("^[-\\s]+", "");
            String[] parts = content.split("\\|", 2);
            String name = parts[0].strip();
            if (name.isBlank() || name.length() > 100) {
                continue;
            }
            Proposal node = new Proposal(name, parts.length > 1 ? parts[1].strip() : "");
            int level = (int) dashes;
            while (stack.size() > level) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                node.parent = stack.peek();
                stack.peek().children.add(node);
            }
            stack.push(node);
        }
        return roots;
    }

    /** Rekursiv anlegen; existierende Namen wiederverwenden statt duplizieren. */
    private void applyProposal(Proposal node, Long parentId,
                               java.util.Map<String, Category> existing, int[] counts) {
        if (node.name == null || node.name.isBlank()) {
            return;
        }
        Category target = existing.get(node.name.strip().toLowerCase());
        if (target == null) {
            target = new Category(currentUser.id(), node.name.strip(),
                    node.description == null ? "" : node.description.strip());
            target.setParentId(parentId);
            target = repository.save(target);
            existing.put(target.getName().toLowerCase(), target);
            counts[0]++;
        } else {
            counts[1]++;   // existiert schon — nicht doppelt anlegen
        }
        for (Proposal child : node.children) {
            applyProposal(child, target.getId(), existing, counts);
        }
    }

    private void refresh() {
        List<Category> roots = treeService.roots(currentUser.id());
        tree.setItems(roots, parent -> treeService.children(currentUser.id(), parent));
        tree.expandRecursively(roots, 5);
    }
}
