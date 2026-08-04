package com.summarizer.graph.ui;

import com.summarizer.base.CurrentUser;
import com.summarizer.graph.GraphExtractionService;
import com.summarizer.graph.GraphService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.List;

/**
 * Wissensgraph-Ansicht: interaktive React-Flow-Visualisierung
 * plus Entitäten-/Beziehungs-Tabellen.
 */
@Route("graph")
@PageTitle("Wissensgraph — Summarizer Studio")
@PermitAll
public class GraphView extends VerticalLayout {

    /** Standard-Farben für Kategorien ohne eigene Farbe (deterministisch nach Kategorie-ID). */
    private static final String[] DEFAULT_PALETTE = {
            "#1a73e8", "#e91e63", "#2e7d32", "#f9a825", "#7b1fa2",
            "#00838f", "#ef6c00", "#5d4037", "#455a64", "#c2185b"};
    private static final String NO_CATEGORY_COLOR = "#9e9e9e";

    private final GraphService graph;
    private final GraphExtractionService extraction;
    private final CurrentUser currentUser;
    private final com.summarizer.base.JobProgressService jobs;
    private final com.summarizer.ai.EmbeddingService embeddings;
    private final GraphFlowComponent flow = new GraphFlowComponent();
    private final Grid<GraphService.Entity> entityGrid = new Grid<>();
    private final Grid<GraphService.Relation> relationGrid = new Grid<>();
    private final com.vaadin.flow.component.textfield.TextField filterField =
            new com.vaadin.flow.component.textfield.TextField();
    private final Div legend = new Div();
    private java.util.Set<Long> filteredEntityIds;   // null = kein Filter
    private final java.util.Set<String> hiddenCategories = new java.util.HashSet<>();
    private final com.vaadin.flow.component.select.Select<Integer> topN =
            new com.vaadin.flow.component.select.Select<>();
    private final com.vaadin.flow.component.checkbox.Checkbox strongEdges =
            new com.vaadin.flow.component.checkbox.Checkbox();
    private boolean strongEdgesInitialized;

    public GraphView(GraphService graph, GraphExtractionService extraction, CurrentUser currentUser,
                     com.summarizer.base.JobProgressService jobs,
                     com.summarizer.ai.EmbeddingService embeddings) {
        this.graph = graph;
        this.extraction = extraction;
        this.currentUser = currentUser;
        this.jobs = jobs;
        this.embeddings = embeddings;
        setPadding(true);
        addClassName("fade-in");
        setSizeFull();

        add(new H2(getTranslation("graph.title")));
        add(new Paragraph(getTranslation("graph.intro")));

        Button backfill = new Button(getTranslation("graph.rebuild"), e -> {
            com.vaadin.flow.component.confirmdialog.ConfirmDialog confirm =
                    new com.vaadin.flow.component.confirmdialog.ConfirmDialog();
            confirm.setHeader(getTranslation("graph.rebuild.confirm.header"));
            confirm.setText(getTranslation("graph.rebuild.confirm.text"));
            confirm.setCancelable(true);
            confirm.setCancelText(getTranslation("graph.cancel"));
            confirm.setConfirmText(getTranslation("graph.rebuild.confirm.button"));
            confirm.setConfirmButtonTheme("error primary");
            confirm.addConfirmListener(ev -> {
                extraction.rebuild(currentUser.id());
                Notification.show(getTranslation("graph.rebuild.started"));
            });
            confirm.open();
        });
        backfill.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button refresh = new Button(getTranslation("graph.refresh"), e -> refresh());
        Button editPrompt = new Button(getTranslation("graph.prompt.edit"), e -> openPromptDialog());
        editPrompt.setTooltipText(getTranslation("graph.prompt.tooltip"));

        filterField.setPlaceholder(getTranslation("graph.filter.placeholder"));
        filterField.setWidth("240px");
        filterField.setClearButtonVisible(true);
        Button applyFilter = new Button(getTranslation("graph.filter.apply"), e -> applySemanticFilter());
        applyFilter.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        applyFilter.addClickShortcut(com.vaadin.flow.component.Key.ENTER);
        Button clearFilter = new Button(getTranslation("graph.filter.clear"), e -> {
            filterField.clear();
            filteredEntityIds = null;
            refresh();
        });

        // Detailgrad: nur die zentralsten Knoten zeigen (0 = alle)
        topN.setItems(30, 60, 100, 0);
        topN.setValue(30);
        topN.setItemLabelGenerator(n -> n == 0
                ? getTranslation("graph.topn.all") : "Top " + n);
        topN.setWidth("110px");
        topN.addValueChangeListener(e -> refresh());

        strongEdges.setLabel(getTranslation("graph.strongEdges"));
        strongEdges.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                refresh();
            }
        });

        HorizontalLayout toolbar = new HorizontalLayout(backfill, refresh, editPrompt,
                filterField, applyFilter, clearFilter, topN, strongEdges);
        toolbar.setAlignItems(Alignment.END);
        toolbar.getStyle().set("flex-wrap", "wrap");
        add(toolbar);
        add(new com.summarizer.base.ui.JobProgressBar(jobs,
                com.summarizer.base.JobProgressService.graphKey(currentUser.id())));

        legend.getStyle().set("display", "flex").set("gap", "0.6em")
                .set("flex-wrap", "wrap").set("margin", "0.3em 0");
        add(legend);

        flow.setSizeFull();

        entityGrid.addColumn(GraphService.Entity::name)
                .setHeader(getTranslation("graph.column.entity")).setAutoWidth(true);
        entityGrid.addColumn(GraphService.Entity::type)
                .setHeader(getTranslation("graph.column.type")).setAutoWidth(true);
        entityGrid.addColumn(GraphService.Entity::degree)
                .setHeader(getTranslation("graph.column.degree")).setAutoWidth(true);
        entityGrid.addColumn(GraphService.Entity::description)
                .setHeader(getTranslation("graph.column.description")).setFlexGrow(1);
        entityGrid.setSizeFull();

        relationGrid.addColumn(GraphService.Relation::sourceName)
                .setHeader(getTranslation("graph.column.from")).setAutoWidth(true);
        relationGrid.addColumn(GraphService.Relation::relation)
                .setHeader(getTranslation("graph.column.relation")).setFlexGrow(1);
        relationGrid.addColumn(GraphService.Relation::targetName)
                .setHeader(getTranslation("graph.column.to")).setAutoWidth(true);
        relationGrid.setSizeFull();

        flow.addNodeClickListener(this::showEntityItems);

        Tab graphTab = new Tab(getTranslation("graph.tab.graph"));
        Tab entitiesTab = new Tab(getTranslation("graph.tab.entities"));
        Tab relationsTab = new Tab(getTranslation("graph.tab.relations"));
        Tabs tabs = new Tabs(graphTab, entitiesTab, relationsTab);
        Div pages = new Div(flow, entityGrid, relationGrid);
        pages.setSizeFull();
        tabs.addSelectedChangeListener(e -> {
            flow.setVisible(e.getSelectedTab() == graphTab);
            entityGrid.setVisible(e.getSelectedTab() == entitiesTab);
            relationGrid.setVisible(e.getSelectedTab() == relationsTab);
        });
        entityGrid.setVisible(false);
        relationGrid.setVisible(false);

        add(tabs, pages);
        setFlexGrow(1, pages);
        refresh();
    }

    /** Knoten-Klick: zugehörige Items als Dialog mit Links zur Detail-Ansicht. */
    private void showEntityItems(long entityId) {
        List<GraphService.LinkedItem> linked = graph.itemsForEntity(currentUser.id(), entityId);
        String entityName = graph.entities(currentUser.id()).stream()
                .filter(e -> e.id() == entityId)
                .map(GraphService.Entity::name)
                .findFirst().orElse(getTranslation("graph.entity"));
        com.vaadin.flow.component.dialog.Dialog dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(getTranslation("graph.items.header", entityName, linked.size()));
        dialog.setWidth("640px");

        if (linked.isEmpty()) {
            dialog.add(new Paragraph(getTranslation("graph.items.empty")));
            dialog.open();
            return;
        }

        // Scrollbare Liste — bleibt auch bei hunderten Einträgen bedienbar
        Div list = new Div();
        list.getStyle().set("display", "flex").set("flex-direction", "column")
                .set("gap", "0.15em").set("max-height", "60vh").set("overflow-y", "auto")
                .set("padding-right", "0.4em");

        java.util.function.Consumer<String> render = filter -> {
            list.removeAll();
            String needle = filter == null ? "" : filter.strip().toLowerCase();
            int shown = 0;
            for (GraphService.LinkedItem item : linked) {
                String title = item.title() == null || item.title().isBlank()
                        ? getTranslation("graph.items.untitled") : item.title();
                if (!needle.isEmpty() && !title.toLowerCase().contains(needle)) {
                    continue;
                }
                com.vaadin.flow.component.html.Anchor link =
                        new com.vaadin.flow.component.html.Anchor("item/" + item.itemId(), "");
                link.add(new com.vaadin.flow.component.html.Span(title));
                com.vaadin.flow.component.html.Span type =
                        new com.vaadin.flow.component.html.Span("  ·  " + item.type());
                type.getStyle().set("color", "var(--vaadin-text-color-secondary, #888)")
                        .set("font-size", "0.85em");
                link.add(type);
                link.getStyle().set("padding", "0.25em 0").set("text-decoration", "none");
                list.add(link);
                shown++;
            }
            if (shown == 0) {
                list.add(new Paragraph(getTranslation("graph.items.nomatch")));
            }
        };

        // Suchfeld erst ab vielen Einträgen einblenden
        if (linked.size() > 12) {
            com.vaadin.flow.component.textfield.TextField search =
                    new com.vaadin.flow.component.textfield.TextField();
            search.setPlaceholder(getTranslation("graph.items.search"));
            search.setWidthFull();
            search.setClearButtonVisible(true);
            search.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);
            search.addValueChangeListener(e -> render.accept(e.getValue()));
            dialog.add(search);
        }

        render.accept("");
        dialog.add(list);
        dialog.open();
    }

    /** Semantische Suche wie im Dashboard → nur Knoten der Treffer-Items bleiben übrig. */
    private void applySemanticFilter() {
        String query = filterField.getValue() == null ? "" : filterField.getValue().strip();
        if (query.isEmpty()) {
            filteredEntityIds = null;
            refresh();
            return;
        }
        // Relative Schwelle: nur Items nahe am besten Treffer (bester + 0.08),
        // absolut gedeckelt — sonst wäre bei kleinen Archiven alles ein "Treffer".
        List<com.summarizer.ai.EmbeddingService.SearchHit> hits =
                embeddings.search(currentUser.id(), query, 20);
        List<Long> itemIds = List.of();
        if (!hits.isEmpty()) {
            double best = hits.getFirst().distance();
            double threshold = Math.min(best + 0.08, 0.55);
            itemIds = hits.stream()
                    .filter(h -> h.distance() <= threshold)
                    .map(com.summarizer.ai.EmbeddingService.SearchHit::itemId)
                    .distinct()
                    .toList();
        }
        filteredEntityIds = graph.entityIdsForItems(currentUser.id(), itemIds);
        if (filteredEntityIds.isEmpty()) {
            Notification.show(getTranslation("graph.filter.nohits"));
        }
        refresh();
    }

    private void refresh() {
        List<GraphService.Entity> entities = graph.entities(currentUser.id());
        List<GraphService.Relation> relations = graph.relations(currentUser.id());

        if (filteredEntityIds != null) {
            entities = entities.stream()
                    .filter(e -> filteredEntityIds.contains(e.id()))
                    .toList();
            java.util.Set<Long> visible = entities.stream()
                    .map(GraphService.Entity::id)
                    .collect(java.util.stream.Collectors.toSet());
            relations = relations.stream()
                    .filter(r -> visible.contains(r.sourceId()) && visible.contains(r.targetId()))
                    .toList();
        }

        updateLegend(entities);
        if (!hiddenCategories.isEmpty()) {
            entities = entities.stream()
                    .filter(e -> !hiddenCategories.contains(
                            e.categoryName() == null ? getTranslation("graph.category.none") : e.categoryName()))
                    .toList();
            java.util.Set<Long> visibleIds = entities.stream()
                    .map(GraphService.Entity::id)
                    .collect(java.util.stream.Collectors.toSet());
            relations = relations.stream()
                    .filter(r -> visibleIds.contains(r.sourceId()) && visibleIds.contains(r.targetId()))
                    .toList();
        }
        entityGrid.setItems(entities);
        relationGrid.setItems(relations);

        // Beim ersten Laden: "starke Kanten" automatisch an, wenn der Graph gross ist
        if (!strongEdgesInitialized) {
            strongEdgesInitialized = true;
            strongEdges.setValue(relations.size() > 80);
        }

        // Detailgrad: Liste ist nach Grad sortiert — Top-N zentrale Knoten behalten
        Integer limit = topN.getValue();
        if (limit != null && limit > 0 && entities.size() > limit) {
            entities = entities.subList(0, limit);
        }
        java.util.Set<Long> visibleForGraph = entities.stream()
                .map(GraphService.Entity::id)
                .collect(java.util.stream.Collectors.toSet());
        List<GraphService.Relation> graphRelations = relations.stream()
                .filter(r -> visibleForGraph.contains(r.sourceId())
                        && visibleForGraph.contains(r.targetId()))
                .filter(r -> !Boolean.TRUE.equals(strongEdges.getValue()) || r.weight() >= 2)
                .toList();

        List<GraphFlowComponent.GraphNode> nodes = entities.stream()
                .map(e -> new GraphFlowComponent.GraphNode(
                        String.valueOf(e.id()), e.name(), e.type(), e.degree(), colorFor(e)))
                .toList();
        List<GraphFlowComponent.GraphEdge> edges = new java.util.ArrayList<>();
        int index = 0;
        for (GraphService.Relation r : graphRelations) {
            edges.add(new GraphFlowComponent.GraphEdge("e" + index++,
                    String.valueOf(r.sourceId()), String.valueOf(r.targetId()),
                    r.relation() == null ? "" : r.relation(), r.weight()));
        }
        flow.setGraph(nodes, edges);
    }

    /** Kategorie-Farbe: eigene Farbe der Kategorie, sonst Standard-Palette, ohne Kategorie grau. */
    private String colorFor(GraphService.Entity entity) {
        if (entity.categoryColor() != null && !entity.categoryColor().isBlank()) {
            return entity.categoryColor();
        }
        if (entity.categoryId() != null) {
            return DEFAULT_PALETTE[(int) (entity.categoryId() % DEFAULT_PALETTE.length)];
        }
        return NO_CATEGORY_COLOR;
    }

    /** Legende als An/Aus-Badges: Klick blendet die Kategorie im Graph aus. */
    private void updateLegend(List<GraphService.Entity> entities) {
        legend.removeAll();
        java.util.Map<String, String> byCategory = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (GraphService.Entity entity : entities) {
            String name = entity.categoryName() == null
                    ? getTranslation("graph.category.none") : entity.categoryName();
            byCategory.putIfAbsent(name, colorFor(entity));
            counts.merge(name, 1, Integer::sum);
        }
        byCategory.forEach((name, color) -> {
            boolean active = !hiddenCategories.contains(name);
            com.vaadin.flow.component.html.Span badge =
                    new com.vaadin.flow.component.html.Span("● " + name + " (" + counts.get(name) + ")");
            badge.getStyle()
                    .set("background", active ? color : "transparent")
                    .set("color", active ? "white" : "var(--vaadin-text-color-secondary, #999)")
                    .set("border", "1px solid " + color)
                    .set("border-radius", "999px")
                    .set("padding", "0.15em 0.8em")
                    .set("font-size", "0.8em")
                    .set("font-weight", "600")
                    .set("cursor", "pointer")
                    .set("opacity", active ? "1" : "0.55")
                    .set("user-select", "none");
            badge.setTitle(active
                    ? getTranslation("graph.legend.hide") : getTranslation("graph.legend.show"));
            badge.getElement().addEventListener("click", e -> {
                if (!hiddenCategories.remove(name)) {
                    hiddenCategories.add(name);
                }
                refresh();
            });
            legend.add(badge);
        });
    }

    /** Prompt-Editor: eigene Extraktions-Anweisung speichern und Graph neu aufbauen. */
    private void openPromptDialog() {
        com.vaadin.flow.component.dialog.Dialog dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(getTranslation("graph.prompt.header"));
        dialog.setWidth("760px");
        dialog.add(new Paragraph(getTranslation("graph.prompt.info")));

        com.vaadin.flow.component.textfield.TextArea area =
                new com.vaadin.flow.component.textfield.TextArea();
        area.setWidthFull();
        // Höhe an den Viewport koppeln, damit der Footer immer sichtbar bleibt
        area.setHeight("min(420px, 45vh)");
        area.setValue(extraction.promptTemplate());

        Button save = new Button(getTranslation("graph.prompt.save"), e -> {
            if (!area.getValue().contains("{{TEXT}}")) {
                Notification.show(getTranslation("graph.prompt.missing"));
                return;
            }
            extraction.setPromptTemplate(area.getValue());
            dialog.close();
            Notification.show(getTranslation("graph.prompt.saved"));
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button saveAndRebuild = new Button(getTranslation("graph.prompt.saveRebuild"), e -> {
            if (!area.getValue().contains("{{TEXT}}")) {
                Notification.show(getTranslation("graph.prompt.missing.short"));
                return;
            }
            extraction.setPromptTemplate(area.getValue());
            extraction.rebuild(currentUser.id());
            dialog.close();
            Notification.show(getTranslation("graph.prompt.savedRebuild"));
        });

        Button reset = new Button(getTranslation("graph.prompt.reset"),
                e -> area.setValue(extraction.defaultPrompt()));
        reset.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button cancel = new Button(getTranslation("graph.cancel"), e -> dialog.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        dialog.add(area);
        // Aktionen in den Footer — sonst überlappen sie bei kleinen Fenstern den Inhalt
        HorizontalLayout footer = new HorizontalLayout(reset, cancel, save, saveAndRebuild);
        footer.setWidthFull();
        footer.setJustifyContentMode(JustifyContentMode.END);
        footer.getStyle().set("flex-wrap", "wrap").set("gap", "0.5em");
        dialog.getFooter().add(footer);
        dialog.open();
    }
}
