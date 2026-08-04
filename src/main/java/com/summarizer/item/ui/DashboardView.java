package com.summarizer.item.ui;

import com.summarizer.base.CurrentUser;
import com.summarizer.category.Category;
import com.summarizer.category.CategoryRepository;
import com.summarizer.category.CategoryTreeService;
import com.summarizer.category.FavoritesService;
import com.summarizer.item.Item;
import com.summarizer.item.ItemQueryService;
import com.summarizer.item.ItemRepository;
import com.summarizer.item.pipeline.IngestPipeline;
import com.summarizer.user.User;
import com.summarizer.user.UserRepository;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Route("")
@PageTitle("Dashboard — Summarizer Studio")
@PermitAll
public class DashboardView extends HorizontalLayout {

    private static final int PAGE_SIZE = 24;
    /** Setting-Schlüssel: max. Links pro Lesezeichen-Import (System-View). */
    public static final String IMPORT_LIMIT_KEY = "import.max-links";
    public static final int IMPORT_LIMIT_DEFAULT = 2000;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final ItemQueryService queries;
    private final ItemRepository itemRepository;
    private final CategoryTreeService categoryTree;
    private final CategoryRepository categoryRepository;
    private final FavoritesService favoritesService;
    private final IngestPipeline pipeline;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final User user;
    private final Category favoritesRoot;

    private final TextField searchField = new TextField();
    private final Checkbox semanticBox = new Checkbox(true);
    private final ComboBox<Category> categoryBox = new ComboBox<>();
    private final ComboBox<Item.Type> typeBox = new ComboBox<>();
    private final com.vaadin.flow.component.combobox.MultiSelectComboBox<String> tagBox =
            new com.vaadin.flow.component.combobox.MultiSelectComboBox<>();
    private final DatePicker fromDate = new DatePicker();
    private final DatePicker toDate = new DatePicker();
    private final Checkbox deadBox = new Checkbox();
    private final Checkbox includeSubs = new Checkbox();

    private final VerticalLayout sidebar = new VerticalLayout();
    private final TreeGrid<Category> favTree = new TreeGrid<>();
    private final TreeGrid<Category> mainTree = new TreeGrid<>();
    private Category selectedTreeCategory;
    private boolean favoritesSelected;

    private final HorizontalLayout sortBar = new HorizontalLayout();
    private final List<String> sortKeys = new ArrayList<>();
    private String draggedSortKey;
    private String viewMode;

    private final Div cardsContainer = new Div();
    private final Button loadMore = new Button();
    private final Span filterInfo = new Span();
    private int offset = 0;

    // Massen-Auswahl
    private final java.util.Set<Long> selectedIds = new java.util.LinkedHashSet<>();
    private final Span selectionInfo = new Span();
    private final HorizontalLayout bulkBar = new HorizontalLayout();
    private final ComboBox<Category> bulkCategory = new ComboBox<>();

    private final com.summarizer.item.TagService tags;

    private final com.summarizer.settings.AppSettingsService settings;
    private final com.summarizer.task.TaskService taskService;
    private final com.summarizer.task.TaskRepository taskRepository;
    private final com.summarizer.item.LinkCheckService linkCheck;

    public DashboardView(ItemQueryService queries, ItemRepository itemRepository,
                         CategoryRepository categories, CategoryTreeService categoryTree,
                         FavoritesService favoritesService, IngestPipeline pipeline,
                         UserRepository userRepository, CurrentUser currentUser,
                         com.summarizer.item.TagService tags,
                         com.summarizer.settings.AppSettingsService settings,
                         com.summarizer.task.TaskService taskService,
                         com.summarizer.task.TaskRepository taskRepository,
                         com.summarizer.item.LinkCheckService linkCheck) {
        this.tags = tags;
        this.settings = settings;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.linkCheck = linkCheck;
        this.categoryRepository = categories;
        this.queries = queries;
        this.itemRepository = itemRepository;
        this.categoryTree = categoryTree;
        this.favoritesService = favoritesService;
        this.pipeline = pipeline;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
        this.user = currentUser.get();
        this.favoritesRoot = favoritesService.ensureExists(user.getId());

        sortKeys.addAll(Arrays.asList(user.getDashboardSort().split(",")));
        viewMode = user.getDashboardView();

        semanticBox.setLabel(getTranslation("dashboard.filter.semantic"));
        loadMore.setText(getTranslation("dashboard.loadMore"));

        setPadding(true);
        addClassName("fade-in");
        setSizeFull();

        buildSidebar();

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSizeFull();
        content.add(buildFilterBar(categories));
        content.add(buildToolbar());
        filterInfo.getStyle().set("font-size", "0.85em").set("font-weight", "600")
                .set("color", "var(--vaadin-text-color-secondary, #555)");
        content.add(filterInfo);
        content.add(buildBulkBar(categories));

        cardsContainer.setWidthFull();
        applyViewMode();
        Div scroller = new Div(cardsContainer, loadMore);
        scroller.getStyle().set("overflow-y", "auto").set("width", "100%").set("flex", "1");
        content.add(scroller);
        content.setFlexGrow(1, scroller);
        loadMore.addClickListener(e -> loadPage());
        loadMore.getStyle().set("margin-top", "0.8em");
        // Infinite Scroll: sobald der Button sichtbar wird, automatisch nachladen
        loadMore.getElement().executeJs(
                "const b = this;"
                + "new IntersectionObserver((entries) => {"
                + "  entries.forEach((entry) => { if (entry.isIntersecting) b.click(); });"
                + "}).observe(b);");

        // Splitter zwischen Kategorien-Sidebar und Kacheln — Breite frei ziehbar
        com.vaadin.flow.component.splitlayout.SplitLayout split =
                new com.vaadin.flow.component.splitlayout.SplitLayout(sidebar, content);
        split.setSizeFull();
        split.setSplitterPosition(20);
        add(split);
        setFlexGrow(1, split);
        reload();
    }

    // ---------- Sidebar: Alle anzeigen / Favoriten / Kategorien ----------

    private void buildSidebar() {
        sidebar.setWidthFull();
        sidebar.getStyle().set("min-width", "170px");
        sidebar.setPadding(false);
        sidebar.setSpacing(false);
        sidebar.addClassName("s-sidebar");
        sidebar.getStyle().set("border-right", "1px solid var(--s-card-border, #ddd)")
                .set("padding-right", "0.4em");
        favTree.addThemeVariants(com.vaadin.flow.component.grid.GridVariant.LUMO_COMPACT,
                com.vaadin.flow.component.grid.GridVariant.LUMO_NO_BORDER,
                com.vaadin.flow.component.grid.GridVariant.LUMO_NO_ROW_BORDERS);
        mainTree.addThemeVariants(com.vaadin.flow.component.grid.GridVariant.LUMO_COMPACT,
                com.vaadin.flow.component.grid.GridVariant.LUMO_NO_BORDER,
                com.vaadin.flow.component.grid.GridVariant.LUMO_NO_ROW_BORDERS);

        categoryCounts = new java.util.HashMap<>();
        for (Object[] row : itemRepository.countPerCategory(user.getId())) {
            categoryCounts.put((Long) row[0], (Long) row[1]);
        }

        Button all = new Button(getTranslation("dashboard.sidebar.showAll"), VaadinIcon.LIST.create(), e -> {
            clearTreeSelection();
            categoryBox.clear();
            reload();
        });
        all.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        all.getStyle().set("font-weight", "600");
        sidebar.add(all);

        favTree.addComponentHierarchyColumn(this::categoryLabel)
                .setHeader(getTranslation("dashboard.sidebar.favorites"));
        favTree.setItems(List.of(favoritesRoot),
                parent -> categoryTree.children(user.getId(), parent));
        favTree.addItemClickListener(e -> handleTreeClick(e.getItem(), favTree, mainTree));
        favTree.setAllRowsVisible(true);
        favTree.expandRecursively(List.of(favoritesRoot), 3);
        sidebar.add(favTree);

        sidebar.add(new Hr());

        mainTree.addComponentHierarchyColumn(this::categoryLabel)
                .setHeader(getTranslation("dashboard.sidebar.categories"));
        List<Category> roots = normalRoots();
        mainTree.setItems(roots, parent -> categoryTree.children(user.getId(), parent));
        mainTree.addItemClickListener(e -> handleTreeClick(e.getItem(), mainTree, favTree));
        mainTree.expandRecursively(roots, 3);
        mainTree.setSizeFull();
        buildTreeContextMenu();

        // Kacheln aus dem Inhaltsbereich hierher ziehen = Kategorie zuweisen
        mainTree.setDropMode(com.vaadin.flow.component.grid.dnd.GridDropMode.ON_TOP);
        mainTree.addDropListener(e -> {
            if (draggedItemId == null) {
                return;
            }
            e.getDropTargetItem().ifPresent(category ->
                    itemRepository.findByIdAndUserId(draggedItemId, user.getId()).ifPresent(item -> {
                        item.setCategoryId(category.getId());
                        item.setCategoryConfidence(1.0f);   // manuell = sicher
                        itemRepository.save(item);
                        Notification.show(getTranslation("dashboard.dnd.assigned",
                                category.getName()));
                        reload();
                    }));
            draggedItemId = null;
        });
        sidebar.add(mainTree);
        sidebar.setFlexGrow(1, mainTree);
    }

    /** Rechtsklick im Kategorien-Baum: umbenennen, Unterkategorie, hoch/runter, löschen. */
    private void buildTreeContextMenu() {
        com.vaadin.flow.component.grid.contextmenu.GridContextMenu<Category> menu =
                mainTree.addContextMenu();
        menu.setDynamicContentHandler(category -> category != null);
        menu.addItem(getTranslation("dashboard.cat.rename"), e ->
                e.getItem().ifPresent(this::openCategoryEditDialog));
        menu.addItem(getTranslation("dashboard.cat.addChild"), e ->
                e.getItem().ifPresent(parent -> openCategoryCreateDialog(parent)));
        menu.addItem(getTranslation("dashboard.cat.moveUp"), e ->
                e.getItem().ifPresent(c -> moveCategory(c, -1)));
        menu.addItem(getTranslation("dashboard.cat.moveDown"), e ->
                e.getItem().ifPresent(c -> moveCategory(c, +1)));
        menu.addItem(getTranslation("dashboard.cat.delete"), e ->
                e.getItem().ifPresent(this::deleteCategoryIfEmpty));
    }

    private void openCategoryEditDialog(Category category) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("dashboard.cat.rename"));
        TextField name = new TextField(getTranslation("dashboard.cat.name"));
        name.setWidthFull();
        name.setValue(category.getName());
        TextField description = new TextField(getTranslation("dashboard.cat.keywords"));
        description.setWidthFull();
        description.setValue(category.getDescription() == null ? "" : category.getDescription());
        Button save = new Button(getTranslation("dashboard.cat.save"), e -> {
            if (name.getValue().isBlank()) {
                name.setInvalid(true);
                return;
            }
            boolean duplicate = categoryRepository
                    .findByUserIdOrderBySortOrderAscNameAsc(user.getId()).stream()
                    .anyMatch(c -> !c.getId().equals(category.getId())
                            && c.getName().equalsIgnoreCase(name.getValue().strip()));
            if (duplicate) {
                name.setInvalid(true);
                name.setErrorMessage(getTranslation("dashboard.cat.nameExists"));
                return;
            }
            category.setName(name.getValue().strip());
            category.setDescription(description.getValue());
            categoryRepository.save(category);
            dialog.close();
            refreshSidebarTree();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(new VerticalLayout(name, description));
        dialog.getFooter().add(save);
        dialog.open();
    }

    private void openCategoryCreateDialog(Category parent) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("dashboard.cat.addChildTitle", parent.getName()));
        TextField name = new TextField(getTranslation("dashboard.cat.name"));
        name.setWidthFull();
        TextField description = new TextField(getTranslation("dashboard.cat.keywords"));
        description.setWidthFull();
        description.setPlaceholder(getTranslation("dashboard.cat.keywordsPlaceholder"));
        Button save = new Button(getTranslation("dashboard.cat.save"), e -> {
            if (name.getValue().isBlank()) {
                name.setInvalid(true);
                return;
            }
            boolean duplicate = categoryRepository
                    .findByUserIdOrderBySortOrderAscNameAsc(user.getId()).stream()
                    .anyMatch(c -> c.getName().equalsIgnoreCase(name.getValue().strip()));
            if (duplicate) {
                name.setInvalid(true);
                name.setErrorMessage(getTranslation("dashboard.cat.nameExists"));
                return;
            }
            Category created = new Category(user.getId(), name.getValue().strip(),
                    description.getValue());
            created.setParentId(parent.getId());
            categoryRepository.save(created);
            dialog.close();
            refreshSidebarTree();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(new VerticalLayout(name, description));
        dialog.getFooter().add(save);
        dialog.open();
    }

    /** Innerhalb der eigenen Ebene eine Position nach oben/unten. */
    private void moveCategory(Category category, int direction) {
        List<Category> siblings = category.getParentId() == null
                ? normalRoots()
                : categoryTree.children(user.getId(),
                        categoryRepository.findById(category.getParentId()).orElse(null));
        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(category.getId())) {
                index = i;
                break;
            }
        }
        int target = index + direction;
        if (index < 0 || target < 0 || target >= siblings.size()) {
            return;   // schon am Rand
        }
        // Stabile Reihenfolge herstellen und die beiden Nachbarn tauschen
        for (int i = 0; i < siblings.size(); i++) {
            siblings.get(i).setSortOrder(i);
        }
        siblings.get(index).setSortOrder(target);
        siblings.get(target).setSortOrder(index);
        categoryRepository.saveAll(siblings);
        refreshSidebarTree();
    }

    private void deleteCategoryIfEmpty(Category category) {
        if (category.isFavorites() || category.isDefaultCategory()) {
            Notification.show(getTranslation("dashboard.cat.protected"), 5000,
                    Notification.Position.MIDDLE);
            return;
        }
        List<Long> subtree = categoryTree.selfAndDescendantIds(user.getId(), category.getId());
        long used = itemRepository.countByCategoryIdIn(subtree);
        if (used > 0) {
            Notification.show(getTranslation("dashboard.cat.notEmpty", String.valueOf(used)),
                    5000, Notification.Position.MIDDLE);
            return;
        }
        categoryRepository.deleteAllById(subtree);
        Notification.show(getTranslation("dashboard.cat.deleted"));
        refreshSidebarTree();
    }

    private void refreshSidebarTree() {
        categoryCounts = new java.util.HashMap<>();
        for (Object[] row : itemRepository.countPerCategory(user.getId())) {
            categoryCounts.put((Long) row[0], (Long) row[1]);
        }
        List<Category> roots = normalRoots();
        mainTree.setItems(roots, parent -> categoryTree.children(user.getId(), parent));
        mainTree.expandRecursively(roots, 3);
        categoryBox.setItems(categoryRepository.findByUserIdOrderBySortOrderAscNameAsc(user.getId()));
        reload();
    }

    private List<Category> normalRoots() {
        return categoryTree.roots(user.getId()).stream()
                .filter(c -> c.getSystemType() == null)
                .toList();
    }

    private java.util.Map<Long, Long> categoryCounts = java.util.Map.of();

    private Span categoryLabel(Category category) {
        Span dot = new Span(category.isFavorites() ? "★ " : "● ");
        dot.getStyle().set("color", category.getColor() == null || category.getColor().isBlank()
                ? "#78909c" : category.getColor());
        // Anzahl direkt zugeordneter Inhalte in Klammern
        long count = categoryCounts.getOrDefault(category.getId(), 0L);
        Span counter = new Span(count > 0 ? " (" + count + ")" : "");
        counter.getStyle().set("color", "var(--vaadin-text-color-secondary, #999)")
                .set("font-size", "0.85em");
        return new Span(dot, new Span(category.getName()), counter);
    }

    /**
     * Zeilen-Klick im Baum: setzt den Filter immer — unabhängig davon, ob das Grid
     * den Klick als Selektion oder als Aufklappen interpretiert.
     * Erneuter Klick auf dieselbe Kategorie hebt den Filter auf.
     */
    private void handleTreeClick(Category clicked, TreeGrid<Category> source, TreeGrid<Category> other) {
        other.deselectAll();
        if (selectedTreeCategory != null && selectedTreeCategory.getId().equals(clicked.getId())) {
            source.deselectAll();
            applyTreeSelection(null);
        } else {
            source.select(clicked);
            applyTreeSelection(clicked);
        }
    }

    private void applyTreeSelection(Category selected) {
        selectedTreeCategory = selected;
        favoritesSelected = selected != null && selected.getId().equals(favoritesRoot.getId());
        if (selected != null) {
            categoryBox.clear();
        }
        reload();
    }

    private void clearTreeSelection() {
        selectedTreeCategory = null;
        favoritesSelected = false;
        favTree.deselectAll();
        mainTree.deselectAll();
    }

    // ---------- Filterleiste ----------

    private com.vaadin.flow.component.Component buildFilterBar(CategoryRepository categories) {
        searchField.setPlaceholder(getTranslation("dashboard.filter.search.placeholder"));
        searchField.setWidth("300px");
        searchField.setPrefixComponent(VaadinIcon.SEARCH.create());
        searchField.setClearButtonVisible(true);
        // Live-Suche: nach kurzer Tipp-Pause automatisch suchen, kein Klick noetig
        searchField.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);
        searchField.setValueChangeTimeout(500);
        searchField.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                reload();
            }
        });

        categoryBox.setPlaceholder(getTranslation("dashboard.filter.category"));
        categoryBox.setItems(categories.findByUserIdOrderBySortOrderAscNameAsc(user.getId()));
        categoryBox.setItemLabelGenerator(Category::getName);
        categoryBox.setClearButtonVisible(true);
        categoryBox.setWidth("160px");
        categoryBox.addValueChangeListener(e -> {
            if (e.isFromClient() && e.getValue() != null) {
                clearTreeSelection();
            }
        });

        typeBox.setPlaceholder(getTranslation("dashboard.filter.type"));
        typeBox.setItems(Item.Type.values());
        typeBox.setClearButtonVisible(true);
        typeBox.setWidth("130px");

        tagBox.setPlaceholder(getTranslation("dashboard.filter.tags"));
        tagBox.setWidth("230px");
        tagBox.setClearButtonVisible(true);
        tagBox.setAllowCustomValue(true);
        tagBox.setItems(tags.allTagNamesUnlimited(user.getId()));
        tagBox.addCustomValueSetListener(e -> {
            String value = e.getDetail().trim();
            if (value.isEmpty()) {
                return;
            }
            java.util.Set<String> selection = new java.util.LinkedHashSet<>(tagBox.getValue());
            selection.add(value);
            tagBox.setValue(selection);
        });

        fromDate.setPlaceholder(getTranslation("dashboard.filter.from"));
        fromDate.setWidth("140px");
        toDate.setPlaceholder(getTranslation("dashboard.filter.to"));
        toDate.setWidth("140px");

        deadBox.setLabel(getTranslation("dashboard.filter.deadLinks"));
        deadBox.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                reload();
            }
        });

        includeSubs.setLabel(getTranslation("dashboard.filter.includeSubs"));
        includeSubs.setValue(true);
        includeSubs.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                reload();
            }
        });

        // Enter = sofort suchen (Live-Suche greift sonst nach 500 ms)
        searchField.addKeyPressListener(com.vaadin.flow.component.Key.ENTER, e -> reload());

        Button reset = new Button(getTranslation("dashboard.filter.reset"), e -> {
            searchField.clear();
            categoryBox.clear();
            typeBox.clear();
            tagBox.clear();
            fromDate.clear();
            toDate.clear();
            deadBox.setValue(false);
            includeSubs.setValue(true);
            clearTreeSelection();
            reload();
        });
        reset.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        // Zeile 1: Suche + Semantik-Schalter, rechts Zurücksetzen
        Div rowSpacer = new Div();
        HorizontalLayout searchRow = new HorizontalLayout(searchField, semanticBox,
                rowSpacer, reset);
        searchRow.setAlignItems(Alignment.CENTER);
        searchRow.setWidthFull();
        searchRow.setFlexGrow(1, rowSpacer);

        // Zeile 2: alle Filter, einheitlich und umbrechend
        FlexLayout filterRow = new FlexLayout(categoryBox, includeSubs, typeBox, tagBox,
                fromDate, toDate, deadBox);
        filterRow.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        filterRow.getStyle().set("gap", "0.4em 0.8em").set("align-items", "center")
                .set("width", "100%");

        Div card = new Div(searchRow, filterRow);
        card.addClassName("s-toolbar");
        card.getStyle().set("width", "100%")
                .set("box-sizing", "border-box")   // sonst ragt "Zurücksetzen" aus dem Rand
                .set("background", "var(--lumo-contrast-5pct, #f4f5f9)")
                .set("border-radius", "12px")
                .set("padding", "0.6em 0.9em")
                .set("display", "flex").set("flex-direction", "column").set("gap", "0.45em");
        return card;
    }

    // ---------- Toolbar: Sortier-Badges (DnD), Ansicht, Import ----------

    private HorizontalLayout buildToolbar() {
        Span label = new Span(getTranslation("dashboard.sort.label"));
        label.addClassName("s-section-label");
        rebuildSortBar();

        Button tiles = new Button(VaadinIcon.GRID_SMALL.create(), e -> switchView("TILES"));
        tiles.setTooltipText(getTranslation("dashboard.view.tiles"));
        Button list = new Button(VaadinIcon.LINES_LIST.create(), e -> switchView("LIST"));
        list.setTooltipText(getTranslation("dashboard.view.list"));

        // Ein "+ Hinzufügen"-Menü statt drei einzelner Buttons — deutlich ruhiger
        com.vaadin.flow.component.menubar.MenuBar addMenu =
                new com.vaadin.flow.component.menubar.MenuBar();
        addMenu.addThemeVariants(com.vaadin.flow.component.menubar.MenuBarVariant.LUMO_PRIMARY);
        var addRoot = addMenu.addItem(getTranslation("dashboard.add.menu"));
        addRoot.getSubMenu().addItem(getTranslation("dashboard.link.button"),
                e -> openAddLinkDialog());
        addRoot.getSubMenu().addItem(getTranslation("dashboard.paste.button"),
                e -> openPasteDialog());
        addRoot.getSubMenu().addItem(getTranslation("dashboard.upload.button"),
                e -> openUploadDialog());
        addRoot.getSubMenu().addItem(getTranslation("dashboard.import.button"),
                e -> openImportDialog());

        Button checkLinks = new Button(VaadinIcon.LINK.create(), e -> {
            linkCheck.checkAll(user.getId());
            Notification.show(getTranslation("dashboard.linkcheck.started"));
        });
        checkLinks.setTooltipText(getTranslation("dashboard.linkcheck.tooltip"));

        Div spacer = new Div();
        HorizontalLayout toolbar = new HorizontalLayout(label, sortBar, spacer, tiles, list,
                checkLinks, addMenu);
        toolbar.setAlignItems(Alignment.CENTER);
        toolbar.setFlexGrow(1, spacer);
        toolbar.setWidthFull();
        toolbar.getStyle().set("flex-wrap", "wrap");
        return toolbar;
    }

    /** Badges in Prioritätsreihenfolge; per Drag&Drop umsortierbar, wird am User gespeichert. */
    private void rebuildSortBar() {
        sortBar.removeAll();
        sortBar.setSpacing(false);
        sortBar.getStyle().set("gap", "0.3em");
        for (String key : sortKeys) {
            Span badge = new Span((sortKeys.indexOf(key) + 1) + ". " + sortLabel(key));
            badge.addClassName("s-sort-badge");
            DragSource<Span> drag = DragSource.create(badge);
            drag.addDragStartListener(e -> draggedSortKey = key);
            DropTarget<Span> drop = DropTarget.create(badge);
            drop.addDropListener(e -> {
                if (draggedSortKey == null || draggedSortKey.equals(key)) {
                    return;
                }
                sortKeys.remove(draggedSortKey);
                sortKeys.add(sortKeys.indexOf(key), draggedSortKey);
                draggedSortKey = null;
                persistPrefs();
                rebuildSortBar();
                reload();
            });
            sortBar.add(badge);
        }
    }

    private String sortLabel(String key) {
        return switch (key) {
            case "DATE" -> getTranslation("dashboard.sort.date");
            case "TYPE" -> getTranslation("dashboard.sort.type");
            case "CATEGORY" -> getTranslation("dashboard.sort.category");
            default -> key;
        };
    }

    private void switchView(String mode) {
        viewMode = mode;
        persistPrefs();
        applyViewMode();
        reload();
    }

    private void applyViewMode() {
        if ("LIST".equals(viewMode)) {
            cardsContainer.getStyle()
                    .set("display", "flex")
                    .set("flex-direction", "column")
                    .set("gap", "0.4em")
                    .remove("grid-template-columns");
        } else {
            cardsContainer.getStyle()
                    .set("display", "grid")
                    .set("grid-template-columns", "repeat(auto-fill, minmax(280px, 1fr))")
                    .set("gap", "0.8em");
        }
    }

    private void persistPrefs() {
        user.setDashboardSort(String.join(",", sortKeys));
        user.setDashboardView(viewMode);
        userRepository.save(user);
    }

    // ---------- Bookmarks-Import (Chrome/Safari HTML-Export) ----------

    /** Limit aus System-Einstellungen (import.max-links), Fallback 2000. */
    private int importLimit() {
        try {
            return Math.max(1, Integer.parseInt(
                    settings.get(IMPORT_LIMIT_KEY, String.valueOf(IMPORT_LIMIT_DEFAULT))));
        } catch (NumberFormatException e) {
            return IMPORT_LIMIT_DEFAULT;
        }
    }

    private void openImportDialog() {
        int limit = importLimit();
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("dashboard.import.dialogTitle"));
        dialog.add(new Paragraph(getTranslation("dashboard.import.dialogText", limit)));

        MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(".html", "text/html");
        upload.setMaxFiles(1);
        upload.addSucceededListener(e -> {
            try {
                Document doc = Jsoup.parse(buffer.getInputStream(), "UTF-8", "");
                List<Element> links = doc.select("a[href^=http]");
                int imported = 0;
                for (Element link : links) {
                    if (imported >= limit) {
                        break;
                    }
                    Item item = new Item(user.getId(), Item.Type.BOOKMARK);
                    item.setSourceUrl(link.attr("href"));
                    String text = link.text();
                    item.setTitle(text.isBlank() ? link.attr("href") : text);
                    itemRepository.save(item);
                    pipeline.process(item.getId());
                    imported++;
                }
                dialog.close();
                Notification.show(getTranslation("dashboard.import.success", imported)
                        + (links.size() > limit
                                ? " " + getTranslation("dashboard.import.skipped", links.size() - limit)
                                : ""));
                reload();
            } catch (Exception ex) {
                Notification.show(getTranslation("dashboard.import.failed", ex.getMessage()));
            }
        });
        dialog.add(upload);
        dialog.open();
    }

    /** Massen-Aktionen: alle/keine auswählen, Kategorie zuweisen, löschen. */
    private HorizontalLayout buildBulkBar(CategoryRepository categories) {
        selectionInfo.getStyle().set("font-size", "0.85em").set("font-weight", "600");

        Button selectAll = new Button(getTranslation("dashboard.bulk.selectAll"), e -> {
            selectedIds.clear();
            queries.find(user.getId(), currentFilter(), 0, 5000)
                    .forEach(card -> selectedIds.add(card.id()));
            reload();
        });
        selectAll.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);

        Button selectNone = new Button(getTranslation("dashboard.bulk.selectNone"), e -> {
            selectedIds.clear();
            reload();
        });
        selectNone.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);

        bulkCategory.setPlaceholder(getTranslation("dashboard.bulk.categoryPlaceholder"));
        bulkCategory.setItems(categories.findByUserIdOrderBySortOrderAscNameAsc(user.getId()));
        bulkCategory.setItemLabelGenerator(Category::getName);
        bulkCategory.setWidth("190px");

        Button assign = new Button(getTranslation("dashboard.bulk.assign"), e -> {
            if (selectedIds.isEmpty() || bulkCategory.getValue() == null) {
                Notification.show(getTranslation("dashboard.bulk.selectFirst"));
                return;
            }
            Category target = bulkCategory.getValue();
            itemRepository.findAllById(selectedIds).forEach(item -> {
                item.setCategoryId(target.getId());
                item.setCategoryConfidence(1.0f);   // manuell = sicher
                itemRepository.save(item);
            });
            Notification.show(getTranslation("dashboard.bulk.assigned", selectedIds.size(), target.getName()));
            selectedIds.clear();
            reload();
        });
        assign.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);

        Button delete = new Button(getTranslation("dashboard.bulk.delete"), e -> {
            if (selectedIds.isEmpty()) {
                return;
            }
            com.vaadin.flow.component.confirmdialog.ConfirmDialog confirm =
                    new com.vaadin.flow.component.confirmdialog.ConfirmDialog();
            confirm.setHeader(getTranslation("dashboard.bulk.deleteConfirmHeader", selectedIds.size()));
            confirm.setText(getTranslation("dashboard.bulk.deleteConfirmText"));
            confirm.setCancelable(true);
            confirm.setCancelText(getTranslation("dashboard.bulk.cancel"));
            confirm.setConfirmText(getTranslation("dashboard.bulk.delete"));
            confirm.setConfirmButtonTheme("error primary");
            confirm.addConfirmListener(ev -> {
                itemRepository.findAllById(selectedIds).forEach(item -> {
                    if (item.getFilePath() != null) {
                        try {
                            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(item.getFilePath()));
                        } catch (Exception ignored) {
                        }
                    }
                    itemRepository.delete(item);
                });
                Notification.show(getTranslation("dashboard.bulk.deleted", selectedIds.size()));
                selectedIds.clear();
                reload();
            });
            confirm.open();
        });
        delete.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);

        bulkBar.add(selectionInfo, selectAll, selectNone, bulkCategory, assign, delete);
        bulkBar.setAlignItems(Alignment.CENTER);
        bulkBar.getStyle().set("flex-wrap", "wrap")
                .set("background", "var(--vaadin-contrast-5pct, #f5f5f5)")
                .set("border-radius", "8px").set("padding", "0.3em 0.8em");
        updateBulkBar();
        return bulkBar;
    }

    private void updateBulkBar() {
        selectionInfo.setText(getTranslation("dashboard.bulk.selected", selectedIds.size()));
        bulkBar.setVisible(true);
    }

    /** Link hinzufügen — YouTube wird transkribiert, alles andere als Lesezeichen geladen. */
    private void openAddLinkDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("dashboard.link.dialogTitle"));
        dialog.setWidth("520px");
        Paragraph hint = new Paragraph(getTranslation("dashboard.link.dialogText"));
        hint.getStyle().set("font-size", "0.9em")
                .set("color", "var(--lumo-secondary-text-color)");

        TextField url = new TextField(getTranslation("dashboard.link.urlField"));
        url.setWidthFull();
        url.setPlaceholder("https://…");
        url.setClearButtonVisible(true);
        TextField title = new TextField(getTranslation("dashboard.paste.titleField"));
        title.setWidthFull();
        title.setPlaceholder(getTranslation("dashboard.paste.titlePlaceholder"));

        Button save = new Button(getTranslation("dashboard.link.save"), e -> {
            String value = url.getValue() == null ? "" : url.getValue().strip();
            if (!value.matches("https?://\\S+")) {
                url.setInvalid(true);
                url.setErrorMessage(getTranslation("dashboard.link.invalid"));
                return;
            }
            Item item = new Item(user.getId(),
                    com.summarizer.item.extract.YouTubeTranscriptService.isYoutubeUrl(value)
                            ? Item.Type.WEBPAGE : Item.Type.BOOKMARK);
            item.setSourceUrl(value);
            if (!title.getValue().isBlank()) {
                item.setTitle(title.getValue().strip());
            }
            itemRepository.save(item);
            pipeline.process(item.getId());
            dialog.close();
            Notification.show(getTranslation(
                    com.summarizer.item.extract.YouTubeTranscriptService.isYoutubeUrl(value)
                            ? "dashboard.link.savedYoutube" : "dashboard.link.saved"));
            reload();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.addClickShortcut(com.vaadin.flow.component.Key.ENTER);

        dialog.add(hint, url, title);
        dialog.getFooter().add(save);
        dialog.open();
        url.focus();
    }

    /** Text aus der Zwischenablage einfügen — läuft durch die komplette Pipeline. */
    private void openPasteDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("dashboard.paste.dialogTitle"));
        dialog.setWidth("640px");
        dialog.add(new Paragraph(getTranslation("dashboard.paste.dialogText")));

        TextField title = new TextField(getTranslation("dashboard.paste.titleField"));
        title.setWidthFull();
        title.setPlaceholder(getTranslation("dashboard.paste.titlePlaceholder"));
        com.vaadin.flow.component.textfield.TextArea text =
                new com.vaadin.flow.component.textfield.TextArea(
                        getTranslation("dashboard.paste.textField"));
        text.setWidthFull();
        text.setHeight("min(320px, 40vh)");

        Button save = new Button(getTranslation("dashboard.paste.save"), e -> {
            String value = text.getValue() == null ? "" : text.getValue().strip();
            if (value.isEmpty()) {
                text.setInvalid(true);
                text.setErrorMessage(getTranslation("dashboard.paste.empty"));
                return;
            }
            // Reine URL -> als Webseite behandeln (wie Telegram/API)
            boolean isUrl = value.matches("https?://\\S+");
            Item item = new Item(user.getId(), isUrl ? Item.Type.WEBPAGE : Item.Type.TEXT);
            if (isUrl) {
                item.setSourceUrl(value);
            } else {
                item.setRawText(value);
            }
            if (!title.getValue().isBlank()) {
                item.setTitle(title.getValue().strip());
            }
            itemRepository.save(item);
            pipeline.process(item.getId());
            dialog.close();
            Notification.show(getTranslation("dashboard.paste.saved"));
            reload();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.addClickShortcut(com.vaadin.flow.component.Key.ENTER,
                com.vaadin.flow.component.KeyModifier.CONTROL);

        dialog.add(title, text);
        dialog.getFooter().add(save);
        dialog.open();
        text.focus();
    }

    /** Datei-Upload direkt im Studio (PDF, Word, Bilder, …). */
    private void openUploadDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("dashboard.upload.dialogTitle"));
        dialog.add(new Paragraph(getTranslation("dashboard.upload.dialogText")));
        MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setMaxFiles(1);
        upload.addSucceededListener(e -> {
            try {
                String original = e.getFileName();
                String extension = original.contains(".")
                        ? original.substring(original.lastIndexOf('.')) : "";
                java.nio.file.Path dir = java.nio.file.Path.of("./data/files",
                        String.valueOf(java.time.LocalDate.now().getYear()));
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path target = dir.resolve(java.util.UUID.randomUUID() + extension);
                java.nio.file.Files.copy(buffer.getInputStream(), target);

                boolean isImage = e.getMIMEType() != null && e.getMIMEType().startsWith("image/");
                Item item = new Item(user.getId(), isImage ? Item.Type.IMAGE : Item.Type.FILE);
                item.setTitle(original);
                item.setFilePath(target.toString());
                itemRepository.save(item);
                pipeline.process(item.getId());
                dialog.close();
                Notification.show(getTranslation("dashboard.upload.success"));
                reload();
            } catch (Exception ex) {
                Notification.show(getTranslation("dashboard.upload.failed", ex.getMessage()));
            }
        });
        dialog.add(upload);
        dialog.open();
    }

    // ---------- Daten laden ----------

    private ItemQueryService.Filter currentFilter() {
        boolean withSubs = Boolean.TRUE.equals(includeSubs.getValue());
        List<Long> categoryIds = null;
        if (selectedTreeCategory != null) {
            categoryIds = withSubs
                    ? categoryTree.selfAndDescendantIds(user.getId(), selectedTreeCategory.getId())
                    : List.of(selectedTreeCategory.getId());
        } else if (categoryBox.getValue() != null) {
            categoryIds = withSubs
                    ? categoryTree.selfAndDescendantIds(user.getId(), categoryBox.getValue().getId())
                    : List.of(categoryBox.getValue().getId());
        }
        return new ItemQueryService.Filter(
                searchField.getValue(),
                semanticBox.getValue(),
                categoryIds,
                false,
                favoritesSelected,
                typeBox.getValue(),
                fromDate.getValue(),
                toDate.getValue(),
                sortKeys,
                List.copyOf(tagBox.getValue()),
                Boolean.TRUE.equals(deadBox.getValue()));
    }

    private void reload() {
        offset = 0;
        cardsContainer.removeAll();
        refreshCategoryCounts();
        updateFilterInfo();
        updateBulkBar();
        loadPage();
    }

    /** Zähler im Baum bei JEDEM Neuladen aktualisieren (DnD, Zuweisen, Filter …). */
    private void refreshCategoryCounts() {
        java.util.Map<Long, Long> fresh = new java.util.HashMap<>();
        for (Object[] row : itemRepository.countPerCategory(user.getId())) {
            fresh.put((Long) row[0], (Long) row[1]);
        }
        if (!fresh.equals(categoryCounts)) {
            categoryCounts = fresh;
            mainTree.getDataProvider().refreshAll();
            favTree.getDataProvider().refreshAll();
        }
    }

    private void updateFilterInfo() {
        if (favoritesSelected) {
            filterInfo.setText(getTranslation("dashboard.filterInfo.favorites"));
        } else if (selectedTreeCategory != null) {
            filterInfo.setText(getTranslation("dashboard.filterInfo.category",
                    selectedTreeCategory.getName()));
        } else {
            filterInfo.setText(getTranslation("dashboard.filterInfo.all"));
        }
    }

    private void loadPage() {
        List<ItemQueryService.Card> cards = queries.find(user.getId(), currentFilter(), offset, PAGE_SIZE);
        if (offset == 0 && cards.isEmpty()) {
            if (isUnfiltered() && itemRepository.countByUserId(user.getId()) == 0) {
                cardsContainer.add(onboarding());
            } else {
                cardsContainer.add(new Paragraph(getTranslation("dashboard.empty")));
            }
        }
        cards.forEach(card -> cardsContainer.add(renderCard(card)));
        offset += cards.size();
        loadMore.setVisible(cards.size() == PAGE_SIZE);
    }

    private boolean isUnfiltered() {
        return selectedTreeCategory == null && !favoritesSelected
                && categoryBox.isEmpty() && typeBox.isEmpty() && tagBox.getValue().isEmpty()
                && !Boolean.TRUE.equals(deadBox.getValue())
                && (searchField.getValue() == null || searchField.getValue().isBlank());
    }

    /** Leerzustand mit Einstieg für neue User. */
    private Div onboarding() {
        Div box = new Div();
        box.getStyle().set("grid-column", "1 / -1").set("max-width", "560px")
                .set("margin", "2em auto").set("padding", "1.5em 2em")
                .set("border", "1px dashed var(--vaadin-border-color, #bbb)")
                .set("border-radius", "12px");
        Span title = new Span(getTranslation("dashboard.onboarding.title"));
        title.getStyle().set("font-weight", "700").set("font-size", "1.1em").set("display", "block")
                .set("margin-bottom", "0.6em");
        box.add(title);
        box.add(new Paragraph(getTranslation("dashboard.onboarding.step1")));
        box.add(new Paragraph(getTranslation("dashboard.onboarding.step2")));
        box.add(new Paragraph(getTranslation("dashboard.onboarding.step3")));
        com.vaadin.flow.router.RouterLink telegram = new com.vaadin.flow.router.RouterLink(
                getTranslation("dashboard.onboarding.telegramLink"),
                com.summarizer.base.ui.SystemView.class);
        telegram.getStyle().set("display", "block").set("margin-bottom", "0.3em");
        box.add(telegram);
        box.add(new com.vaadin.flow.router.RouterLink(getTranslation("dashboard.onboarding.tokensLink"),
                com.summarizer.token.ui.TokensView.class));
        return box;
    }

    // ---------- Karten ----------

    /** Gerade gezogene Kachel (Item-ID) — Ziel ist der Kategorien-Baum links. */
    private Long draggedItemId;

    private Div renderCard(ItemQueryService.Card card) {
        Div div = new Div();
        div.addClassNames("s-card", "stagger-item");
        div.getStyle().set("overflow", "hidden");   // nichts ragt in Nachbarkarten

        // Kachel per Drag&Drop auf eine Kategorie im Baum ziehen
        com.vaadin.flow.component.dnd.DragSource<Div> drag =
                com.vaadin.flow.component.dnd.DragSource.create(div);
        drag.setDraggable(true);
        drag.addDragStartListener(e -> draggedItemId = card.id());
        drag.addDragEndListener(e -> draggedItemId = null);
        div.addClickListener(e -> UI.getCurrent().navigate(ItemDetailView.class, card.id()));

        // Vorschaubild: og:image der Webseite ODER das Bild selbst bei IMAGE-Items
        String thumbSrc = card.thumbnailUrl() != null && !card.thumbnailUrl().isBlank()
                ? card.thumbnailUrl()
                : ("IMAGE".equals(card.type()) ? "files/" + card.id() : null);
        if (thumbSrc != null && !"LIST".equals(viewMode)) {
            com.vaadin.flow.component.html.Image thumb =
                    new com.vaadin.flow.component.html.Image(thumbSrc, "");
            thumb.getStyle().set("width", "100%").set("height", "130px")
                    .set("object-fit", "cover").set("border-radius", "8px");
            thumb.getElement().setAttribute("loading", "lazy");
            thumb.getElement().executeJs("this.addEventListener('error', () => this.remove())");
            div.add(thumb);
        }

        HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setWidthFull();
        titleRow.setAlignItems(Alignment.START);

        // Auswahl-Checkbox für Massen-Aktionen
        Checkbox select = new Checkbox(selectedIds.contains(card.id()));
        select.getElement().executeJs("this.addEventListener('click', e => e.stopPropagation())");
        select.addValueChangeListener(e -> {
            if (Boolean.TRUE.equals(e.getValue())) {
                selectedIds.add(card.id());
            } else {
                selectedIds.remove(card.id());
            }
            updateBulkBar();
        });
        titleRow.add(select);

        Span title = new Span(card.title() == null || card.title().isBlank()
                ? getTranslation("dashboard.card.noTitle") : card.title());
        title.getStyle().set("font-weight", "600").set("line-height", "1.3")
                // lange URLs/Pfade umbrechen statt in die Nachbarkarte zu laufen
                .set("overflow-wrap", "anywhere").set("word-break", "break-word")
                .set("min-width", "0");
        titleRow.add(title);
        titleRow.setFlexGrow(1, title);
        titleRow.add(taskButton(card));
        titleRow.add(starButton(card));

        Div meta = new Div();
        meta.getStyle().set("display", "flex").set("gap", "0.5em")
                .set("align-items", "center").set("flex-wrap", "wrap");
        // Favicon der Quell-Domain bei Webseiten/Bookmarks
        String domain = extractDomain(card.sourceUrl());
        if (domain != null) {
            com.vaadin.flow.component.html.Image favicon = new com.vaadin.flow.component.html.Image(
                    "https://www.google.com/s2/favicons?sz=32&domain=" + domain, "");
            favicon.setWidth("16px");
            favicon.setHeight("16px");
            favicon.getElement().executeJs("this.addEventListener('error', () => this.remove())");
            meta.add(favicon);
        }
        meta.add(typeBadge(this, card.type()));
        if ("FAILED".equals(card.status())) {
            Span failed = new Span(getTranslation("dashboard.card.failed"));
            failed.getStyle().set("color", "#c62828").set("font-size", "0.8em");
            meta.add(failed);
        } else if ("PENDING".equals(card.status()) || "PROCESSING".equals(card.status())) {
            Span processing = new Span("⏳");
            processing.getStyle().set("font-size", "0.8em");
            meta.add(processing);
        }
        if (Boolean.FALSE.equals(card.linkOk())) {
            Span deadLink = new Span(getTranslation("dashboard.card.deadLink"));
            deadLink.getStyle().set("color", "#c62828").set("font-size", "0.8em")
                    .set("font-weight", "700");
            meta.add(deadLink);
        }
        if (card.categoryName() != null) {
            meta.add(categoryBadge(card.categoryName(), card.categoryColor()));
        }
        Span date = new Span(DATE_FORMAT.format(card.createdAt()));
        date.getStyle().set("color", "var(--vaadin-text-color-secondary, #666)")
                .set("font-size", "0.8em");
        meta.add(date);
        // Quell-Link direkt aus der Kachel öffnen (ohne Detail-Navigation)
        if (card.sourceUrl() != null && card.sourceUrl().startsWith("http")) {
            com.vaadin.flow.component.html.Anchor open =
                    new com.vaadin.flow.component.html.Anchor(card.sourceUrl(),
                            getTranslation("dashboard.card.open"));
            open.setTarget("_blank");
            open.getStyle().set("font-size", "0.8em").set("font-weight", "600");
            open.getElement().executeJs("this.addEventListener('click', e => e.stopPropagation())");
            meta.add(open);
        }
        if (card.distance() != null) {
            Span dist = new Span("≈ %.2f".formatted(1 - card.distance()));
            dist.getStyle().set("font-size", "0.8em")
                    .set("color", "var(--vaadin-text-color-secondary, #666)");
            meta.add(dist);
        }

        div.add(titleRow, meta);
        // Zusammenfassung bevorzugt, sonst Roh-Snippet
        String preview = card.summary() != null && !card.summary().isBlank()
                ? card.summary() : card.snippet();
        if (preview != null && !preview.isBlank()) {
            Span snippet = new Span(preview);
            snippet.getStyle().set("font-size", "0.85em")
                    .set("color", "var(--vaadin-text-color-secondary, #555)")
                    .set("overflow", "hidden")
                    .set("display", "-webkit-box")
                    .set("-webkit-line-clamp", "LIST".equals(viewMode) ? "1" : "3")
                    .set("-webkit-box-orient", "vertical");
            div.add(snippet);
        }
        if (card.tags() != null && !card.tags().isBlank()) {
            Div tagRow = new Div();
            tagRow.getStyle().set("display", "flex").set("gap", "0.3em").set("flex-wrap", "wrap");
            for (String tag : card.tags().split(",")) {
                Span chip = new Span("#" + tag.strip());
                chip.addClassName("s-tag");
                tagRow.add(chip);
            }
            div.add(tagRow);
        }
        return div;
    }

    private String extractDomain(String url) {
        if (url == null || !url.startsWith("http")) {
            return null;
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** Kleines Icon auf der Kachel: Inhalt einer Aufgabe zuordnen oder neue anlegen. */
    private Button taskButton(ItemQueryService.Card card) {
        Button task = new Button("☑");
        task.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        task.getStyle().set("color", "var(--vaadin-text-color-secondary, #999)")
                .set("font-size", "1.0em");
        task.setTooltipText(getTranslation("dashboard.card.task"));
        task.getElement().executeJs("this.addEventListener('click', e => e.stopPropagation())");
        task.addClickListener(e -> openAssignTaskDialog(card.id()));
        return task;
    }

    /** Aufgabe zuordnen: bestehende wählen oder direkt neue anlegen. */
    private void openAssignTaskDialog(Long itemId) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("detail.tasks.assignHeader"));
        dialog.setWidth("420px");

        ComboBox<com.summarizer.task.Task> existing =
                new ComboBox<>(getTranslation("detail.tasks.existing"));
        existing.setItems(taskRepository.findByUserIdAndStatusNotOrderByDueDateAscIdAsc(
                user.getId(), com.summarizer.task.Task.Status.DONE));
        existing.setItemLabelGenerator(com.summarizer.task.Task::getTitle);
        existing.setWidthFull();

        TextField newTitle = new TextField(getTranslation("detail.tasks.newTitle"));
        newTitle.setWidthFull();

        Button save = new Button(getTranslation("detail.tasks.create"), e -> {
            com.summarizer.task.Task target = existing.getValue();
            if (target == null && !newTitle.getValue().isBlank()) {
                target = new com.summarizer.task.Task(user.getId(), newTitle.getValue().strip());
                target.setStartDate(java.time.LocalDate.now());
                target.setDueDate(java.time.LocalDate.now().plusDays(7));
                target = taskRepository.save(target);
            }
            if (target == null) {
                return;
            }
            taskService.linkItem(itemId, target.getId());
            dialog.close();
            Notification.show(getTranslation("detail.tasks.assigned"));
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        dialog.add(new VerticalLayout(existing, newTitle));
        dialog.getFooter().add(save);
        dialog.open();
    }

    private Button starButton(ItemQueryService.Card card) {
        Button star = new Button(card.favorite() ? "★" : "☆");
        star.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        star.getStyle().set("color", card.favorite() ? "#f9a825" : "var(--vaadin-text-color-secondary, #999)")
                .set("font-size", "1.1em");
        star.setTooltipText(card.favorite()
                ? getTranslation("dashboard.card.unfavorite")
                : getTranslation("dashboard.card.favorite"));
        // Klick auf den Stern darf nicht zur Detail-Ansicht navigieren
        star.getElement().executeJs("this.addEventListener('click', e => e.stopPropagation())");
        star.addClickListener(e -> itemRepository.findByIdAndUserId(card.id(), user.getId())
                .ifPresent(item -> {
                    item.setFavorite(!item.isFavorite());
                    itemRepository.save(item);
                    star.setText(item.isFavorite() ? "★" : "☆");
                    star.getStyle().set("color", item.isFavorite() ? "#f9a825"
                            : "var(--vaadin-text-color-secondary, #999)");
                }));
        return star;
    }

    static Span typeBadge(com.vaadin.flow.component.Component context, String type) {
        Span span = new Span(switch (type) {
            case "WEBPAGE" -> context.getTranslation("dashboard.type.webpage");
            case "BOOKMARK" -> context.getTranslation("dashboard.type.bookmark");
            case "IMAGE" -> context.getTranslation("dashboard.type.image");
            case "FILE" -> context.getTranslation("dashboard.type.file");
            case "AUDIO" -> context.getTranslation("dashboard.type.audio");
            default -> context.getTranslation("dashboard.type.text");
        });
        span.getStyle().set("font-size", "0.8em");
        return span;
    }

    static Span categoryBadge(String name, String color) {
        Span span = new Span(name);
        span.addClassName("s-chip");
        span.getStyle().set("background", color == null || color.isBlank() ? "#78909c" : color);
        return span;
    }
}
