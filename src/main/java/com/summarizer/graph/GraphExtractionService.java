package com.summarizer.graph;

import com.summarizer.ai.LlmRouter;
import com.summarizer.item.Item;
import com.summarizer.item.ItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extrahiert Entitäten und Beziehungen per LLM (GraphRAG-Stil).
 * Zeilenformat statt JSON — robust für kleine Modelle:
 *   ENTITY|Name|TYP|Beschreibung
 *   REL|Quelle|Ziel|Beziehung
 */
@Service
public class GraphExtractionService {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractionService.class);
    private static final int MAX_CONTENT_CHARS = 4000;
    private static final Set<String> TYPES = Set.of("PERSON", "ORG", "TECH", "ORT", "CONCEPT");

    private final LlmRouter llm;
    private final GraphService graph;
    private final ItemRepository items;
    private final com.summarizer.category.CategoryRepository categories;
    private final com.summarizer.item.TagService tags;
    private final com.summarizer.base.JobProgressService progress;
    private final com.summarizer.settings.AppSettingsService settings;

    public GraphExtractionService(LlmRouter llm, GraphService graph, ItemRepository items,
                                  com.summarizer.category.CategoryRepository categories,
                                  com.summarizer.item.TagService tags,
                                  com.summarizer.settings.AppSettingsService settings,
                                  com.summarizer.base.JobProgressService progress) {
        this.settings = settings;
        this.llm = llm;
        this.graph = graph;
        this.items = items;
        this.categories = categories;
        this.tags = tags;
        this.progress = progress;
    }

    public void extract(Item item) {
        // Titel und Zusammenfassung zuerst — sie enthalten die zentralen Begriffe
        // und überleben so die Längenbegrenzung des Prompts.
        String text = (item.getTitle() == null ? "" : "Titel: " + item.getTitle() + "\n")
                + (item.getSummary() == null ? "" : "Kurzfassung: " + item.getSummary() + "\n")
                + (item.getRawText() == null ? "" : item.getRawText());
        if (text.isBlank()) {
            return;
        }
        Map<String, Long> entityIds = new HashMap<>();
        // Kategorie und Tags des Items sind selbst Themen — sie verbinden Inhalte
        // über Item-Grenzen hinweg zu einem echten Netz.
        Long topicId = linkTopics(item, entityIds);
        String answer = llm.generate(promptTemplate()
                .replace("{{GUARD}}", com.summarizer.ai.PromptSanitizer.GUARD_NOTE)
                .replace("{{TEXT}}", com.summarizer.ai.PromptSanitizer.wrapUntrusted(text, MAX_CONTENT_CHARS)));
        if (answer == null || answer.isBlank()) {
            return;
        }
        applyExtraction(item, answer, entityIds, topicId);
    }

    /** Aktuell verwendeter Prompt — anpassbar im Studio (Wissensgraph → Prompt). */
    public String promptTemplate() {
        return settings.get(PROMPT_KEY, DEFAULT_PROMPT);
    }

    public void setPromptTemplate(String prompt) {
        settings.set(PROMPT_KEY, prompt == null || prompt.isBlank() ? DEFAULT_PROMPT : prompt.strip());
    }

    public String defaultPrompt() {
        return DEFAULT_PROMPT;
    }

    public static final String PROMPT_KEY = "graph.prompt";

    private static final String DEFAULT_PROMPT = """
                Extrahiere die wichtigsten Themen, Entitäten und ihre Beziehungen aus dem Text.

                Was ist eine Entität:
                - Substantive und Eigennamen: Personen, Firmen, Produkte, Technologien,
                  Algorithmen, Verfahren, Orte, Fachbegriffe, Themen.
                - Auch übergeordnete Themen nennen (z. B. "Sprachmodell", "Photovoltaik",
                  "Italienische Küche") — sie verbinden Inhalte miteinander.

                NIEMALS als Entität:
                - Verben oder Partizipien (erstellt, gestellt, verwendet, zeigt)
                - inhaltsleere Wörter (Zweck, Sache, Thema, Beispiel, Inhalt, Text)
                - ganze Sätze oder Beschreibungen

                Typen:
                  PERSON  = Menschen
                  ORG     = Firmen, Organisationen, Projekte
                  TECH    = Technologien, Produkte, Algorithmen, Verfahren, Werkzeuge
                  ORT     = Orte, Länder, Regionen
                  CONCEPT = Themen, Fachgebiete, abstrakte Begriffe

                Regeln:
                - 5 bis 10 Entitäten, 5 bis 10 Beziehungen.
                - Jede Entität soll ein kurzer Begriff sein (1-4 Wörter), keine Erklärung.
                - Beziehungen zwischen den genannten Entitäten, mit aussagekräftigem Verb
                  (z. B. "ist Teil von", "wird trainiert mit", "gehört zum Thema").

                Antworte NUR mit Zeilen in diesen zwei Formaten (keine Erklärung):
                ENTITY|Name|TYP|kurze Beschreibung
                REL|Name der Quelle|Name des Ziels|Beziehung in 2-5 Worten

                {{GUARD}}

                Text:
                {{TEXT}}
                """;

    /** Antwortzeilen des LLM in Entitäten und Beziehungen überführen. */
    private void applyExtraction(Item item, String answer, Map<String, Long> entityIds, Long topicId) {
        int relations = 0;
        for (String line : answer.strip().lines().toList()) {
            String[] parts = line.strip().split("\\|");
            try {
                if (parts.length >= 3 && parts[0].equalsIgnoreCase("ENTITY")) {
                    String name = clean(parts[1]);
                    if (name.isBlank() || name.length() > 200) {
                        continue;
                    }
                    String type = parts[2].strip().toUpperCase();
                    if (!TYPES.contains(type)) {
                        type = "CONCEPT";
                    }
                    if (isJunkEntity(name)) {
                        continue;
                    }
                    String description = parts.length > 3 ? clean(parts[3]) : null;
                    long id = graph.upsertEntity(item.getUserId(), name, type, description);
                    graph.linkItem(item.getId(), id);
                    entityIds.put(name.toLowerCase(), id);
                    // Jede Entität hängt am Thema des Items -> Inhalte werden verknüpft
                    if (topicId != null && topicId != id) {
                        graph.addRelation(item.getUserId(), id, topicId, "gehört zum Thema", item.getId());
                    }
                } else if (parts.length >= 3 && parts[0].equalsIgnoreCase("REL")) {
                    Long source = resolveEntity(item, entityIds, clean(parts[1]));
                    Long target = resolveEntity(item, entityIds, clean(parts[2]));
                    String relation = parts.length >= 4 ? clean(parts[3]) : "verbunden mit";
                    if (source != null && target != null && !source.equals(target)) {
                        graph.addRelation(item.getUserId(), source, target, relation, item.getId());
                        relations++;
                    }
                }
            } catch (Exception e) {
                log.debug("Graph-Zeile übersprungen: '{}' ({})", line, e.getMessage());
            }
        }
        log.info("Item {}: {} Entitäten, {} Beziehungen extrahiert", item.getId(), entityIds.size(), relations);
    }

    /**
     * Graph komplett neu aufbauen: bestehende Entitäten/Beziehungen löschen,
     * dann alle fertigen Inhalte frisch extrahieren (Studio-Button).
     */
    @Async
    public void rebuild(Long userId) {
        String key = com.summarizer.base.JobProgressService.graphKey(userId);
        graph.deleteAllForUser(userId);
        List<Item> all = items.findByUserIdAndStatus(userId, Item.Status.DONE);
        if (all.isEmpty()) {
            progress.start(key, "Graph neu aufbauen", 0);
            progress.finish(key, "Keine Inhalte vorhanden");
            return;
        }
        progress.start(key, "Graph neu aufbauen", all.size());
        int done = 0;
        for (Item item : all) {
            extract(item);
            progress.update(key, ++done);
        }
        progress.finish(key, "Graph neu aufgebaut aus " + done + " Inhalten");
        log.info("Graph-Rebuild für User {}: {} Items verarbeitet", userId, done);
    }

    /**
     * Kategorie und Tags des Items als Themen-Knoten anlegen und verknüpfen.
     * Gibt die Kategorie-Entität zurück (Aufhänger für die Item-Entitäten).
     */
    private Long linkTopics(Item item, Map<String, Long> entityIds) {
        Long categoryEntityId = null;
        try {
            if (item.getCategoryId() != null) {
                String name = categories.findById(item.getCategoryId())
                        .map(com.summarizer.category.Category::getName).orElse(null);
                if (name != null && !isJunkEntity(name)) {
                    categoryEntityId = graph.upsertEntity(item.getUserId(), name, "CONCEPT",
                            "Kategorie im Archiv");
                    graph.linkItem(item.getId(), categoryEntityId);
                    entityIds.put(name.toLowerCase(), categoryEntityId);
                }
            }
            for (String tag : tags.tagsForItem(item.getId())) {
                if (isJunkEntity(tag)) {
                    continue;
                }
                long tagId = graph.upsertEntity(item.getUserId(), tag, "CONCEPT", "Thema aus Tags");
                graph.linkItem(item.getId(), tagId);
                entityIds.put(tag.toLowerCase(), tagId);
                if (categoryEntityId != null && categoryEntityId != tagId) {
                    graph.addRelation(item.getUserId(), tagId, categoryEntityId,
                            "Teilthema von", item.getId());
                }
            }
        } catch (Exception e) {
            log.debug("Themen-Verknüpfung für Item {} übersprungen: {}", item.getId(), e.getMessage());
        }
        return categoryEntityId;
    }

    /** Verben, Partizipien und inhaltsleere Begriffe aussortieren. */
    private boolean isJunkEntity(String name) {
        String n = name.strip().toLowerCase();
        if (n.length() < 2 || n.split("\\s+").length > 5) {
            return true;
        }
        if (JUNK_WORDS.contains(n)) {
            return true;
        }
        // typische Partizipien/Verbformen: erstellt, gestellt, verwendet, zeigt ...
        return n.matches("^(ge\\w+|\\w+(iert|isiert))$") && !n.contains(" ");
    }

    private static final java.util.Set<String> JUNK_WORDS = java.util.Set.of(
            "zweck", "sache", "thema", "themen", "beispiel", "inhalt", "text", "sonstiges",
            "erstellt", "gestellt", "verwendet", "zeigt", "beschreibt", "enthält", "nutzt",
            "information", "informationen", "daten", "sonstige", "allgemein", "diverses",
            "bild", "foto", "datei", "dokument", "webseite", "artikel", "notiz");

    /**
     * Entität einer REL-Zeile auflösen. Kleine Modelle schreiben Namen leicht
     * anders als in der ENTITY-Zeile — daher exakt, dann Teilstring, dann anlegen.
     */
    private Long resolveEntity(Item item, Map<String, Long> entityIds, String rawName) {
        if (rawName.isBlank() || rawName.length() > 200) {
            return null;
        }
        String name = rawName.toLowerCase();
        Long exact = entityIds.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Long> entry : entityIds.entrySet()) {
            String known = entry.getKey();
            if (known.length() > 2 && (known.contains(name) || name.contains(known))) {
                return entry.getValue();
            }
        }
        // In der REL-Zeile genannt, aber nicht als ENTITY gelistet -> nachtragen
        long id = graph.upsertEntity(item.getUserId(), rawName, "CONCEPT", null);
        graph.linkItem(item.getId(), id);
        entityIds.put(name, id);
        return id;
    }

    private String clean(String value) {
        return value == null ? "" : value.strip().replaceAll("^[\"'*-]+|[\"'*-]+$", "");
    }
}
