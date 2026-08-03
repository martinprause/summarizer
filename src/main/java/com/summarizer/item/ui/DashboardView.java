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
    private static final int IMPORT_LIMIT = 500;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final ItemQueryService queries;
    private final ItemRepository itemRepository;
    private final CategoryTreeService categoryTree;
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

    public DashboardView(ItemQueryService queries, ItemRepository itemRepository,
                         CategoryRepository categories, CategoryTreeService categoryTree,
                         FavoritesService favoritesService, IngestPipeline pipeline,
                         UserRepository userRepository, CurrentUser currentUser,
                         com.summarizer.item.TagService tags) {
        this.tags = tags;
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
        add(sidebar);

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

        add(content);
        setFlexGrow(1, content);
        reload();
    }

    // ---------- Sidebar: Alle anzeigen / Favoriten / Kategorien ----------

    private void buildSidebar() {
        sidebar.setWidth("270px");
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
        sidebar.add(mainTree);
        sidebar.setFlexGrow(1, mainTree);
    }

    private List<Category> normalRoots() {
        return categoryTree.roots(user.getId()).stream()
                .filter(c -> c.getSystemType() == null)
                .toList();
    }

    private Span categoryLabel(Category category) {
        Span dot = new Span(category.isFavorites() ? "★ " : "● ");
        dot.getStyle().set("color", category.getColor() == null || category.getColor().isBlank()
                ? "#78909c" : category.getColor());
        return new Span(dot, new Span(category.getName()));
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

    private FlexLayout buildFilterBar(CategoryRepository categories) {
        searchField.setPlaceholder(getTranslation("dashboard.filter.search.placeholder"));
        searchField.setWidth("220px");
        searchField.setClearButtonVisible(true);

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

        Button search = new Button(getTranslation("dashboard.filter.searchButton"), e -> reload());
        search.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        search.addClickShortcut(com.vaadin.flow.component.Key.ENTER);
        Button reset = new Button(getTranslation("dashboard.filter.reset"), e -> {
            searchField.clear();
            categoryBox.clear();
            typeBox.clear();
            tagBox.clear();
            fromDate.clear();
            toDate.clear();
            clearTreeSelection();
            reload();
        });

        FlexLayout bar = new FlexLayout(searchField, semanticBox, categoryBox, typeBox,
                tagBox, fromDate, toDate, search, reset);
        bar.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        bar.addClassName("s-toolbar");
        bar.getStyle().set("gap", "0.5em").set("align-items", "end").set("width", "100%");
        return bar;
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

        Button importButton = new Button(getTranslation("dashboard.import.button"),
                VaadinIcon.UPLOAD.create(), e -> openImportDialog());
        importButton.setTooltipText(getTranslation("dashboard.import.tooltip"));

        Button uploadButton = new Button(getTranslation("dashboard.upload.button"),
                VaadinIcon.FILE_ADD.create(), e -> openUploadDialog());
        uploadButton.setTooltipText(getTranslation("dashboard.upload.tooltip"));

        Div spacer = new Div();
        HorizontalLayout toolbar = new HorizontalLayout(label, sortBar, spacer, tiles, list,
                importButton, uploadButton);
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

    private void openImportDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("dashboard.import.dialogTitle"));
        dialog.add(new Paragraph(getTranslation("dashboard.import.dialogText", IMPORT_LIMIT)));

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
                    if (imported >= IMPORT_LIMIT) {
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
                        + (links.size() > IMPORT_LIMIT
                                ? " " + getTranslation("dashboard.import.skipped", links.size() - IMPORT_LIMIT)
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
        List<Long> categoryIds = null;
        if (selectedTreeCategory != null) {
            categoryIds = categoryTree.selfAndDescendantIds(user.getId(), selectedTreeCategory.getId());
        } else if (categoryBox.getValue() != null) {
            categoryIds = categoryTree.selfAndDescendantIds(user.getId(), categoryBox.getValue().getId());
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
                List.copyOf(tagBox.getValue()));
    }

    private void reload() {
        offset = 0;
        cardsContainer.removeAll();
        updateFilterInfo();
        updateBulkBar();
        loadPage();
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
        box.add(new com.vaadin.flow.router.RouterLink(getTranslation("dashboard.onboarding.tokensLink"),
                com.summarizer.token.ui.TokensView.class));
        return box;
    }

    // ---------- Karten ----------

    private Div renderCard(ItemQueryService.Card card) {
        Div div = new Div();
        div.addClassNames("s-card", "stagger-item");
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
        title.getStyle().set("font-weight", "600").set("line-height", "1.3");
        titleRow.add(title);
        titleRow.setFlexGrow(1, title);
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
