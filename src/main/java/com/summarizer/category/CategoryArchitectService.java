package com.summarizer.category;

import com.summarizer.ai.ClassificationService;
import com.summarizer.ai.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Kategorie-Architekt": greift, wenn die normale Klassifikation unter der
 * Konfidenz-Schwelle bleibt. Prüft zuerst, ob doch eine bestehende Kategorie
 * (auch Ober-/Unterkategorie) passt — sonst legt er GENAU EINE neue an und
 * hängt sie unter die passendste bestehende Oberkategorie.
 * Leitplanken: Junk-Namen verboten, max. Tiefe 3, Budget pro Lauf.
 */
@Service
public class CategoryArchitectService {

    private static final Logger log = LoggerFactory.getLogger(CategoryArchitectService.class);
    private static final int MAX_DEPTH = 3;

    public static final String ENABLED_KEY = "category.architect.enabled";
    public static final String THRESHOLD_KEY = "category.architect.threshold";
    public static final String MAX_NEW_KEY = "category.architect.max-new";

    private static final Set<String> JUNK_NAMES = Set.of(
            "sonstiges", "sonstige", "diverses", "allgemein", "verschiedenes", "andere",
            "artikel", "inhalte", "inhalt", "dokumente", "dateien", "links", "webseiten",
            "software", "internet", "wissen", "neues", "unsortiert", "import", "neu");

    private final LlmRouter llm;
    private final ClassificationService classification;
    private final CategoryRepository categories;
    private final com.summarizer.settings.AppSettingsService settings;
    /** Budget neuer Kategorien pro User und Lauf (Import bzw. Neu-Kategorisierung). */
    private final Map<Long, AtomicInteger> createdThisRun = new ConcurrentHashMap<>();

    public CategoryArchitectService(LlmRouter llm, ClassificationService classification,
                                    CategoryRepository categories,
                                    com.summarizer.settings.AppSettingsService settings) {
        this.llm = llm;
        this.classification = classification;
        this.categories = categories;
        this.settings = settings;
    }

    public boolean enabled() {
        return !"false".equals(settings.get(ENABLED_KEY, "true"));
    }

    /** Schwelle 0..1, Standard 0.70. */
    public float threshold() {
        try {
            return Math.clamp(Float.parseFloat(settings.get(THRESHOLD_KEY, "0.70")), 0.1f, 1.0f);
        } catch (NumberFormatException e) {
            return 0.70f;
        }
    }

    private int maxNewPerRun() {
        try {
            return Integer.parseInt(settings.get(MAX_NEW_KEY, "15"));
        } catch (NumberFormatException e) {
            return 15;
        }
    }

    /** Budget zurücksetzen — am Anfang eines Imports bzw. Neu-Kategorisierens. */
    public void resetBudget(Long userId) {
        createdThisRun.remove(userId);
    }

    public int createdCount(Long userId) {
        AtomicInteger counter = createdThisRun.get(userId);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Passende Kategorie finden oder neu anlegen.
     * @return Kategorie (bestehend oder neu) oder empty, wenn nichts Sinnvolles geht.
     */
    public Optional<Category> place(Long userId, String title, String summary, String content,
                                    List<Category> userCategories) {
        if (userCategories.isEmpty() || !enabled()) {
            return Optional.empty();
        }
        String excerpt = ((title == null ? "" : title + "\n")
                + (summary == null ? "" : summary + "\n")
                + (content == null ? "" : content));
        if (excerpt.isBlank()) {
            return Optional.empty();
        }
        String prompt = """
                Du organisierst die Kategorien eines persönlichen Wissensarchivs.
                Der folgende Inhalt passte nicht sicher in den bestehenden Baum.

                Bestehende Kategorien (Pfade "Eltern > Kind"):
                %s
                Entscheide dich für GENAU EINE Antwortzeile:
                EXISTING|Kategoriepfad          — wenn eine bestehende doch gut passt
                NEW|Name|Beschreibung|Elternpfad — neue Kategorie, eingehängt unter die
                                                   passendste bestehende Kategorie.
                                                   Kein passender Elternteil: ROOT statt Pfad.

                Regeln für NEW:
                - Name: 1-3 Wörter, ein THEMENGEBIET ("Solarenergie", "Computer Vision") —
                  NIEMALS der Titel, Fachbegriff oder Produktname des Dokuments selbst.
                - Beschreibung: kurze Einsortier-Anweisung plus 3-6 allgemeine Schlagworte
                  zum Themengebiet, kommagetrennt. KEINE Zusammenfassung des Dokuments,
                  KEIN Satz über das Dokument.
                - Lieber EXISTING als eine fast gleiche neue Kategorie.

                SCHLECHT: NEW|Visiontransformer|Der Vision Transformer (ViT) ist eine Architektur für die Bildklassifikation|…
                GUT:      NEW|Computer Vision|Bilderkennung und visuelle KI: Bildklassifikation, Objekterkennung, ViT, CNN|Forschung

                %s

                Inhalt:
                %s
                """.formatted(classification.categoryListing(userCategories),
                com.summarizer.ai.PromptSanitizer.GUARD_NOTE,
                com.summarizer.ai.PromptSanitizer.wrapUntrusted(excerpt, 2500));

        String answer = llm.generate(prompt);
        if (answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        for (String rawLine : answer.strip().lines().toList()) {
            String line = rawLine.strip();
            if (line.regionMatches(true, 0, "EXISTING|", 0, 9)) {
                return classification.matchLine(line.substring(9).strip() + "|0.75", userCategories)
                        .map(ClassificationService.Result::category);
            }
            if (line.regionMatches(true, 0, "NEW|", 0, 4)) {
                return createNew(userId, line.substring(4), userCategories);
            }
        }
        return Optional.empty();
    }

    private Optional<Category> createNew(Long userId, String payload, List<Category> userCategories) {
        String[] parts = payload.split("\\|");
        if (parts.length < 1 || parts[0].isBlank()) {
            return Optional.empty();
        }
        String name = parts[0].strip();
        String description = parts.length > 1 ? parts[1].strip() : "";
        String parentPath = parts.length > 2 ? parts[2].strip() : "ROOT";

        if (name.length() > 60 || name.split("\\s+").length > 3
                || JUNK_NAMES.contains(name.toLowerCase())) {
            log.info("Architekt: Kategorie-Name '{}' abgelehnt (Junk/zu lang)", name);
            return Optional.empty();
        }
        // Dokument-Sätze als "Beschreibung" abfangen ("Der Vision Transformer ist …")
        // — dann lieber leere Beschreibung als eine irreführende
        if (description.length() > 180
                || description.toLowerCase().matches("^(der|die|das|ein|eine|dieser|diese|dieses|this|the)\\s.*")
                || description.matches(".*\\b(ist|sind|wird|werden|is|are)\\b.*[.!]$")) {
            log.info("Architekt: Beschreibung von '{}' verworfen (Dokument-Satz statt Schlagworte)", name);
            description = "";
        }
        // Existiert schon? Dann wiederverwenden statt neu
        Optional<Category> existing = userCategories.stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .findFirst();
        if (existing.isPresent()) {
            return existing;
        }
        // Budget pro Lauf
        AtomicInteger counter = createdThisRun.computeIfAbsent(userId, k -> new AtomicInteger());
        if (counter.get() >= maxNewPerRun()) {
            log.info("Architekt: Budget erschöpft ({} neue Kategorien) — keine weitere", counter.get());
            return Optional.empty();
        }

        // Zu tief? Dann den Pfad hochklettern und an der tiefsten erlaubten
        // Stelle einhängen (statt die Kategorie auf die Wurzel zu werfen)
        Map<Long, Category> byId = new HashMap<>();
        userCategories.forEach(c -> byId.put(c.getId(), c));
        Category parent = resolveParent(parentPath, userCategories);
        while (parent != null && depth(parent, userCategories) >= MAX_DEPTH) {
            parent = parent.getParentId() == null ? null : byId.get(parent.getParentId());
        }
        Category created = new Category(userId, name, description);
        created.setParentId(parent == null ? null : parent.getId());
        created = categories.save(created);
        counter.incrementAndGet();
        log.info("Architekt: neue Kategorie '{}'{} angelegt", name,
                parent == null ? "" : " unter '" + parent.getName() + "'");
        return Optional.of(created);
    }

    private Category resolveParent(String parentPath, List<Category> userCategories) {
        if (parentPath.isBlank() || parentPath.equalsIgnoreCase("ROOT")) {
            return null;
        }
        String last = parentPath.contains(">")
                ? parentPath.substring(parentPath.lastIndexOf('>') + 1).strip() : parentPath;
        return userCategories.stream()
                .filter(c -> c.getName().equalsIgnoreCase(last))
                .findFirst().orElse(null);
    }

    private int depth(Category category, List<Category> all) {
        Map<Long, Category> byId = new HashMap<>();
        all.forEach(c -> byId.put(c.getId(), c));
        int depth = 1;
        Long parentId = category.getParentId();
        while (parentId != null && byId.containsKey(parentId) && depth < 10) {
            depth++;
            parentId = byId.get(parentId).getParentId();
        }
        return depth;
    }
}
