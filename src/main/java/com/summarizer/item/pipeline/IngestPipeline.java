package com.summarizer.item.pipeline;

import com.summarizer.ai.ClassificationService;
import com.summarizer.ai.EmbeddingService;
import com.summarizer.ai.LlmRouter;
import com.summarizer.ai.OllamaClient;
import com.summarizer.category.Category;
import com.summarizer.category.CategoryRepository;
import com.summarizer.category.FavoritesService;
import com.summarizer.graph.GraphExtractionService;
import com.summarizer.item.Item;
import com.summarizer.item.ItemRepository;
import com.summarizer.item.extract.WebPageExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asynchrone Verarbeitung: Extraktion → Zusammenfassung → Klassifikation →
 * Vektorisierung → Graph-Extraktion. Bilder werden per Vision-Modell beschrieben.
 */
@Component
public class IngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

    private final ItemRepository items;
    private final CategoryRepository categories;
    private final FavoritesService favorites;
    private final WebPageExtractor extractor;
    private final com.summarizer.item.extract.FileTextExtractor fileExtractor;
    private final ClassificationService classification;
    private final EmbeddingService embeddings;
    private final GraphExtractionService graphExtraction;
    private final LlmRouter llm;
    private final OllamaClient ollama;
    private final com.summarizer.ai.WhisperClient whisper;
    private final com.summarizer.item.TagService tags;
    private final com.summarizer.base.JobProgressService progress;
    private final Path filesDir;
    private final com.summarizer.item.extract.YouTubeTranscriptService youtube;
    private final com.summarizer.category.CategoryArchitectService architect;
    private final com.summarizer.category.TagVotingService tagVoting;

    public IngestPipeline(ItemRepository items, CategoryRepository categories,
                          FavoritesService favorites, WebPageExtractor extractor,
                          com.summarizer.item.extract.FileTextExtractor fileExtractor,
                          ClassificationService classification, EmbeddingService embeddings,
                          GraphExtractionService graphExtraction, LlmRouter llm,
                          OllamaClient ollama, com.summarizer.ai.WhisperClient whisper,
                          com.summarizer.item.TagService tags,
                          com.summarizer.base.JobProgressService progress,
                          @Value("${summarizer.files.dir}") String filesDir,
                          com.summarizer.item.extract.YouTubeTranscriptService youtube,
                          com.summarizer.category.CategoryArchitectService architect,
                          com.summarizer.category.TagVotingService tagVoting) {
        this.youtube = youtube;
        this.architect = architect;
        this.tagVoting = tagVoting;
        this.progress = progress;
        this.fileExtractor = fileExtractor;
        this.whisper = whisper;
        this.tags = tags;
        this.items = items;
        this.categories = categories;
        this.favorites = favorites;
        this.extractor = extractor;
        this.classification = classification;
        this.embeddings = embeddings;
        this.graphExtraction = graphExtraction;
        this.llm = llm;
        this.ollama = ollama;
        this.filesDir = Path.of(filesDir);
    }

    @Async
    public void process(Long itemId) {
        Item item = items.findById(itemId).orElse(null);
        if (item == null) {
            return;
        }
        // Ohne erreichbares Ollama entstuenden leere "DONE"-Items (keine Zusammenfassung,
        // keine Vektoren). Item bleibt PENDING — der PipelineResumer holt es nach,
        // sobald Ollama wieder da ist.
        if (!ollama.isAvailable()) {
            if (item.getStatus() != Item.Status.PENDING) {
                item.setStatus(Item.Status.PENDING);
                items.save(item);
            }
            log.warn("Item {}: Ollama nicht erreichbar — Verarbeitung zurückgestellt", itemId);
            return;
        }
        item.setStatus(Item.Status.PROCESSING);
        item.setErrorMessage(null);
        items.save(item);
        try {
            extractIfNeeded(item);
            describeImageIfNeeded(item);
            extractFileTextIfNeeded(item);
            transcribeAudioIfNeeded(item);
            analyzeCombined(item);   // Zusammenfassung + Kategorie + Tags in EINEM LLM-Aufruf
            vectorize(item);
            // Massen-Import: Graph-Extraktion aufschieben — der Backfill-Job in
            // PipelineResumer holt sie nach, sobald der Import durch ist.
            if (items.countByStatus(Item.Status.PENDING) <= BULK_THRESHOLD) {
                try {
                    graphExtraction.extract(item);
                } catch (Exception e) {
                    log.warn("Graph-Extraktion für Item {} fehlgeschlagen: {}", itemId, e.getMessage());
                }
            }
            item.setStatus(Item.Status.DONE);
        } catch (Exception e) {
            log.error("Pipeline für Item {} fehlgeschlagen", itemId, e);
            item.setStatus(Item.Status.FAILED);
            item.setErrorMessage(shorten(e.toString()));
        }
        items.save(item);
    }

    private void extractIfNeeded(Item item) {
        boolean isWeb = item.getType() == Item.Type.WEBPAGE || item.getType() == Item.Type.BOOKMARK;
        if (!isWeb || item.getSourceUrl() == null) {
            return;
        }
        // YouTube: Untertitel bzw. Whisper-Transkript statt nutzloser Consent-Seite
        if (com.summarizer.item.extract.YouTubeTranscriptService.isYoutubeUrl(item.getSourceUrl())
                && (item.getRawText() == null || item.getRawText().isBlank())) {
            var transcript = youtube.fetch(item.getSourceUrl());
            if (transcript.isPresent()) {
                var t = transcript.get();
                if ((item.getTitle() == null || item.getTitle().isBlank()) && !t.title().isBlank()) {
                    item.setTitle("▶ " + t.title());
                }
                item.setRawText(t.text());
                com.summarizer.item.extract.YouTubeTranscriptService.videoId(item.getSourceUrl())
                        .ifPresent(id -> item.setThumbnailUrl(
                                com.summarizer.item.extract.YouTubeTranscriptService.thumbnailUrl(id)));
                log.info("Item {}: YouTube-Transkript via {} ({} Zeichen)",
                        item.getId(), t.source(), t.text().length());
                return;
            }
            item.setErrorMessage("YouTube: kein Transkript verfügbar (Untertitel fehlen"
                    + (youtube.isAvailable() ? " und Whisper-Fallback erfolglos)" : ", yt-dlp nicht installiert)"));
            // weiter mit normaler Extraktion — liefert wenigstens den Seitentitel
        }
        try {
            WebPageExtractor.Extracted extracted = extractor.extract(item.getSourceUrl());
            if (item.getTitle() == null || item.getTitle().isBlank()) {
                item.setTitle(extracted.title());
            }
            if (item.getRawText() == null || item.getRawText().isBlank()) {
                item.setRawText(extracted.text());
            }
            if (extracted.thumbnailUrl() != null) {
                item.setThumbnailUrl(extracted.thumbnailUrl());
            }
            saveSnapshot(item, extracted.html());
        } catch (Exception e) {
            item.setErrorMessage(shorten("Extraktion: " + e.getMessage()));
            log.warn("Extraktion von {} fehlgeschlagen: {}", item.getSourceUrl(), e.getMessage());
        }
        if (item.getTitle() == null || item.getTitle().isBlank()) {
            item.setTitle(item.getSourceUrl());
        }
    }

    /** Offline-Kopie des Roh-HTML ablegen. */
    private void saveSnapshot(Item item, String html) {
        if (html == null || html.isBlank()) {
            return;
        }
        try {
            Path dir = filesDir.resolve("snapshots").resolve(String.valueOf(LocalDate.now().getYear()));
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID() + ".html");
            Files.writeString(target, html);
            item.setSnapshotPath(target.toString());
        } catch (Exception e) {
            log.warn("Snapshot für Item {} fehlgeschlagen: {}", item.getId(), e.getMessage());
        }
    }

    /** Bilder: Beschreibung + Textinhalt (OCR) über das Vision-Modell → durchsuchbar. */
    private void describeImageIfNeeded(Item item) {
        boolean hasUsableText = item.getRawText() != null && !item.getRawText().isBlank()
                && !isVisionRefusal(item.getRawText());
        if (item.getType() != Item.Type.IMAGE || item.getFilePath() == null || hasUsableText) {
            return;
        }
        try {
            byte[] image = Files.readAllBytes(Path.of(item.getFilePath()));
            // Englische Instruktion — Vision-Modelle wie llava verweigern bei
            // deutschen OCR-Aufforderungen deutlich häufiger.
            String description = ollama.generateWithImage(
                    "Describe this image precisely in German (2-4 sentences). "
                    + "If there is any visible text in the image, transcribe it verbatim afterwards.",
                    image);
            if (isVisionRefusal(description)) {
                description = ollama.generateWithImage(
                        "Describe this image in detail. Answer in German.", image);
            }
            if (isVisionRefusal(description)) {
                item.setErrorMessage("Vision-Modell hat die Bildbeschreibung verweigert — "
                        + "anderes Modell unter KI-Modelle versuchen (z. B. llama3.2-vision)");
                return;
            }
            // llava & Co. antworten oft englisch — Chat-LLM normalisiert auf Deutsch
            if (description.matches("(?is).*\\b(the|this)\\s+image\\b.*")
                    || description.strip().startsWith("The ")) {
                String german = llm.generate("""
                        Übersetze die folgende Bildbeschreibung ins Deutsche.
                        Wörtlich zitierten Text (OCR) unverändert lassen. Nur die Übersetzung ausgeben.

                        %s
                        """.formatted(description));
                if (german != null && !german.isBlank() && !isVisionRefusal(german)) {
                    description = german;
                }
            }
            item.setRawText(description.strip());
        } catch (Exception e) {
            log.warn("Bildbeschreibung für Item {} fehlgeschlagen: {}", item.getId(), e.getMessage());
        }
    }

    /** Erkennung typischer Verweigerungs-Floskeln (de/en) statt echter Beschreibung. */
    private boolean isVisionRefusal(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String start = text.strip().toLowerCase();
        return start.startsWith("es tut mir leid") || start.startsWith("entschuldigung")
                || start.startsWith("i'm sorry") || start.startsWith("i am sorry")
                || start.startsWith("i cannot") || start.startsWith("i can't")
                || start.startsWith("ich kann keine") || start.startsWith("leider kann ich");
    }

    /** Dokumente (PDF, Word, …): Text via Tika — danach normale Pipeline. */
    private void extractFileTextIfNeeded(Item item) {
        if (item.getType() != Item.Type.FILE || item.getFilePath() == null
                || (item.getRawText() != null && !item.getRawText().isBlank())) {
            return;
        }
        fileExtractor.extract(Path.of(item.getFilePath()))
                .ifPresentOrElse(item::setRawText,
                        () -> log.info("Item {}: kein Text extrahierbar ({})",
                                item.getId(), item.getTitle()));
    }

    /** Audio: Whisper-Transkription — danach normale Pipeline (Summary, Kategorie, Vektoren). */
    private void transcribeAudioIfNeeded(Item item) {
        if (item.getType() != Item.Type.AUDIO || item.getFilePath() == null
                || (item.getRawText() != null && !item.getRawText().isBlank())) {
            return;
        }
        whisper.transcribe(Path.of(item.getFilePath()))
                .ifPresentOrElse(transcript -> {
                    item.setRawText(transcript);
                    if (item.getTitle() == null || item.getTitle().isBlank()
                            || item.getTitle().matches(".*\\.(m4a|mp3|wav|ogg|aac|webm)$")) {
                        item.setTitle("🎙 " + transcript.substring(0, Math.min(60, transcript.length()))
                                + (transcript.length() > 60 ? "…" : ""));
                    }
                }, () -> {
                    item.setErrorMessage("Whisper nicht erreichbar oder Transkription fehlgeschlagen "
                            + "(Container-Profil \"whisper\" gestartet?)");
                    log.warn("Item {}: keine Transkription", item.getId());
                });
    }

    /** Ab so vielen wartenden Items gilt "Massen-Import" — Graph später nachziehen. */
    public static final int BULK_THRESHOLD = 20;

    /**
     * Kombinierter LLM-Aufruf: Zusammenfassung, Kategorie und Tags in einer Antwort
     * (drei Zeilen SUMMARY|/CATEGORY|/TAGS|) — spart zwei von drei LLM-Runden.
     * Fehlt eine Zeile, greifen die Einzelschritte als Fallback.
     */
    private void analyzeCombined(Item item) {
        String text = (item.getTitle() == null ? "" : item.getTitle() + "\n")
                + (item.getRawText() == null ? "" : item.getRawText());
        if (text.isBlank()) {
            return;
        }
        List<Long> favoriteIds = favorites.subtreeIds(item.getUserId());
        List<Category> userCategories = categories
                .findByUserIdOrderBySortOrderAscNameAsc(item.getUserId()).stream()
                .filter(c -> !favoriteIds.contains(c.getId()))
                .toList();
        List<String> existingTags = tags.allTagNames(item.getUserId());

        boolean transcript = isTranscript(item);
        int maxBullets = transcript ? 10 : 15;
        String prompt = """
                Analysiere den folgenden Inhalt.

                Liefere:
                - summary: Beschreibung des Inhalts als %s Stichpunkte.
                  Jeder Stichpunkt EIN kurzer, prägnanter deutscher Satz mit einer Kernaussage.
                  Keine Wiederholungen, kein Fließtext, keine Einleitung.
                - category_path: EXAKT einer der folgenden Kategoriepfade.
                  Wähle die Kategorie, deren Beschreibung am besten passt — eine
                  Unterkategorie NUR, wenn ihre Beschreibung klar zutrifft, im Zweifel
                  die Oberkategorie. NIEMALS eine thematisch falsche Unterkategorie.
                - confidence: wie sicher die Kategorie passt (0 bis 1).
                - tags: 2-5 kleingeschriebene Schlagworte, WORUM ES GEHT
                  (Themen, Orte, Personen, Konzepte). VERBOTEN: Medium/Format/Quelle
                  (bild, video, pdf, telegram, webseite ...).
                  Bevorzuge vorhandene Tags, wenn sie passen: %s

                Kategorien (hierarchisch, "Eltern > Kind"):
                %s
                %s

                Inhalt:
                %s
                """.formatted(
                transcript ? "maximal 10" : "10 bis 15",
                existingTags.isEmpty() ? "(noch keine vorhanden)" : String.join(", ", existingTags),
                userCategories.isEmpty() ? "(keine Kategorien definiert)"
                        : classification.categoryListing(userCategories),
                com.summarizer.ai.PromptSanitizer.GUARD_NOTE,
                com.summarizer.ai.PromptSanitizer.wrapUntrusted(text, 5000));

        // Structured Output: Ollama erzwingt valides JSON nach diesem Schema;
        // enum zwingt category_path auf einen existierenden Pfad
        Map<String, Object> categoryPathSchema = userCategories.isEmpty()
                ? Map.of("type", "string")
                : Map.of("type", "string", "enum", classification.pathList(userCategories));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "summary", Map.of("type", "array",
                                "items", Map.of("type", "string"), "maxItems", maxBullets),
                        "category_path", categoryPathSchema,
                        "confidence", Map.of("type", "number"),
                        "tags", Map.of("type", "array",
                                "items", Map.of("type", "string"), "maxItems", 5)),
                "required", List.of("summary", "category_path", "confidence", "tags"));

        String answer = ollama.generate(prompt, schema);
        String summaryLine = null;
        String categoryLine = null;
        String tagLine = null;
        if (answer != null && !answer.isBlank()) {
            try {
                var node = new tools.jackson.databind.ObjectMapper().readTree(answer);
                if (node.has("summary")) {
                    summaryLine = bulletList(node.get("summary"));
                }
                if (node.has("category_path")) {
                    categoryLine = node.get("category_path").asText()
                            + "|" + (node.has("confidence") ? node.get("confidence").asText() : "0.5");
                }
                if (node.has("tags") && node.get("tags").isArray()) {
                    StringBuilder csv = new StringBuilder();
                    node.get("tags").forEach(tag -> {
                        if (!csv.isEmpty()) {
                            csv.append(',');
                        }
                        csv.append(tag.asText());
                    });
                    tagLine = csv.toString();
                }
            } catch (Exception e) {
                log.warn("Item {}: Structured-Output nicht lesbar ({}) — Einzelschritte greifen",
                        item.getId(), e.getMessage());
            }
        }

        String raw = item.getRawText();
        boolean wantsSummary = raw != null && raw.length() >= 200;
        if (wantsSummary && summaryLine != null && !summaryLine.isBlank()) {
            item.setSummary(summaryLine);
        } else if (wantsSummary) {
            summarize(item);   // Fallback: Einzelaufruf
        }

        if (categoryLine != null && !userCategories.isEmpty()) {
            classification.matchLine(categoryLine, userCategories).ifPresent(result -> {
                item.setCategoryId(result.category().getId());
                item.setCategoryConfidence(result.confidence());
            });
        }
        if (item.getCategoryId() == null && !userCategories.isEmpty()) {
            classify(item);    // Fallback
        }

        if (tagLine != null && !tagLine.isBlank()) {
            applyTagLine(item, tagLine);
        }
        if (tags.tagsForItem(item.getId()).isEmpty()) {
            autoTag(item);     // Fallback
        }

        // Unter der Schwelle? Erst das billige Tag-Voting (kein LLM), dann der Architekt
        float threshold = architect.threshold();
        if (isWeaklyCategorized(item, threshold)) {
            tagVoting.vote(item.getUserId(), item.getId(), favoriteIds).ifPresent(vote -> {
                item.setCategoryId(vote.categoryId());
                item.setCategoryConfidence(vote.confidence());
            });
        }
        if (isWeaklyCategorized(item, threshold) && architect.enabled() && !userCategories.isEmpty()) {
            architect.place(item.getUserId(), item.getTitle(), item.getSummary(),
                            item.getRawText(), userCategories)
                    .ifPresent(category -> {
                        item.setCategoryId(category.getId());
                        item.setCategoryConfidence(threshold);
                    });
        }
    }

    private boolean isWeaklyCategorized(Item item, float threshold) {
        return item.getCategoryId() == null
                || item.getCategoryConfidence() == null
                || item.getCategoryConfidence() < threshold;
    }

    /** Kommagetrennte Tag-Zeile filtern und setzen (gemeinsame Regeln mit autoTag). */
    private void applyTagLine(Item item, String csv) {
        List<String> parsed = java.util.Arrays.stream(csv.split(","))
                .map(t -> t.strip().toLowerCase()
                        .replaceAll("^#", "")
                        .replaceAll("[<>\"'.]", ""))
                .filter(t -> !t.isBlank() && t.length() <= 40 && t.split("\\s+").length <= 3)
                .filter(t -> !BLOCKED_TAGS.contains(t))
                .distinct()
                .limit(5)
                .toList();
        if (!parsed.isEmpty()) {
            tags.setTags(item.getUserId(), item.getId(), parsed);
        }
    }

    private static final java.util.Set<String> BLOCKED_TAGS = java.util.Set.of(
            "bild", "foto", "fotos", "video", "audio", "sprachnotiz", "sprachnachricht",
            "telegram", "datei", "dateien", "pdf", "dokument", "webseite", "website",
            "text", "screenshot", "notiz", "aufnahme", "upload", "link", "bookmark",
            // Anweisungswoerter, die kleine Modelle aus dem Prompt nachplappern
            "kommagetrennt", "schlagworte", "schlagwörter", "schlagwort", "tags", "tag",
            "keywords", "keyword", "kleingeschrieben", "beispiel", "tag1", "tag2", "tag3",
            "schlagwort1", "schlagwort2", "schlagwort3");

    /** Stichpunkt-Zusammenfassung — der Namensgeber der App. */
    private void summarize(Item item) {
        String text = item.getRawText();
        if (text == null || text.isBlank() || text.length() < 200) {
            return;   // Kurztexte brauchen keine Zusammenfassung
        }
        boolean transcript = isTranscript(item);
        String answer = llm.generate("""
                Beschreibe den folgenden Inhalt als %s Stichpunkte.
                Jeder Stichpunkt EIN kurzer, prägnanter deutscher Satz mit einer Kernaussage.
                Keine Wiederholungen, kein Fließtext, keine Einleitung.

                %s

                %s
                """.formatted(transcript ? "maximal 10" : "10 bis 15",
                com.summarizer.ai.PromptSanitizer.GUARD_NOTE,
                com.summarizer.ai.PromptSanitizer.wrapUntrusted(text, 6000)),
                Map.of("type", "object",
                        "properties", Map.of("summary", Map.of("type", "array",
                                "items", Map.of("type", "string"),
                                "maxItems", transcript ? 10 : 15)),
                        "required", List.of("summary")));
        if (answer == null || answer.isBlank()) {
            return;
        }
        try {
            var node = new tools.jackson.databind.ObjectMapper().readTree(answer);
            String summary = node.has("summary") ? bulletList(node.get("summary")) : null;
            if (summary != null && !summary.isBlank()) {
                item.setSummary(summary);
            }
        } catch (Exception e) {
            log.debug("Item {}: Summary-JSON nicht lesbar", item.getId());
        }
    }

    /**
     * Alle fertigen Items neu zusammenfassen (Stichpunkt-Format) — sequenziell,
     * ein LLM-Aufruf nach dem anderen. Geänderte Items werden neu vektorisiert.
     */
    @Async
    public void resummarizeAll(Long userId) {
        List<Item> all = items.findByUserIdAndStatus(userId, Item.Status.DONE).stream()
                .filter(i -> i.getRawText() != null && i.getRawText().length() >= 200)
                .toList();
        String key = com.summarizer.base.JobProgressService.resummarizeKey(userId);
        progress.start(key, "Neu zusammenfassen", all.size());
        int done = 0;
        int updated = 0;
        for (Item item : all) {
            if (!ollama.isAvailable()) {
                progress.finish(key, updated + " aktualisiert — abgebrochen, Ollama nicht erreichbar");
                com.summarizer.base.UiNotifier.broadcast(
                        "📝 Neu zusammenfassen abgebrochen: Ollama nicht erreichbar ("
                                + updated + " bereits aktualisiert)");
                return;
            }
            String before = item.getSummary();
            try {
                summarize(item);
            } catch (Exception e) {
                log.warn("Neu zusammenfassen für Item {} fehlgeschlagen: {}",
                        item.getId(), e.getMessage());
            }
            if (!java.util.Objects.equals(before, item.getSummary())) {
                items.save(item);
                try {
                    vectorize(item);   // Summary steckt mit im Embedding-Text
                } catch (Exception e) {
                    log.warn("Re-Vektorisierung für Item {} fehlgeschlagen: {}",
                            item.getId(), e.getMessage());
                }
                updated++;
            }
            progress.update(key, ++done);
        }
        String result = updated + " von " + all.size() + " aktualisiert";
        progress.finish(key, result);
        com.summarizer.base.UiNotifier.broadcast("📝 Neu zusammengefasst: " + result);
        log.info("Neu zusammenfassen für User {}: {}", userId, result);
    }

    /** Transkript-Items (Audio/YouTube): Volltext ist das Transkript, Beschreibung kürzer. */
    private boolean isTranscript(Item item) {
        if (item.getType() == Item.Type.AUDIO) {
            return true;
        }
        return item.getSourceUrl() != null
                && com.summarizer.item.extract.YouTubeTranscriptService.isYoutubeUrl(item.getSourceUrl());
    }

    /** JSON-Array (oder String) in "• "-Stichpunktzeilen überführen. */
    static String bulletList(tools.jackson.databind.JsonNode node) {
        if (node == null) {
            return null;
        }
        if (!node.isArray()) {
            String text = node.asText("").strip();
            return text.isBlank() ? null : text;
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : node) {
            String line = entry.asText("").strip().replaceAll("^[-•*]\\s*", "");
            if (line.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("• ").append(line);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** Ein String-Feld aus einer Structured-Output-Antwort ziehen (null-sicher). */
    static String jsonString(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var node = new tools.jackson.databind.ObjectMapper().readTree(json);
            return node.has(field) ? node.get(field).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Alle fertigen Items neu klassifizieren — zweistufig:
     * Pass 1 ordnet gegen den bestehenden Baum zu (Schwelle aus den Einstellungen),
     * Pass 2 lässt den Architekten für die unsicheren Fälle Kategorien finden/anlegen.
     * Favoriten-Teilbaum bleibt unangetastet; manuell Zugeordnete (Konfidenz 1.0)
     * optional ebenfalls.
     */
    @Async
    public void reclassifyAll(Long userId, boolean keepManual, boolean allowNew) {
        List<Long> favoriteIds = favorites.subtreeIds(userId);
        List<Item> all = items.findByUserIdAndStatus(userId, Item.Status.DONE);
        String key = com.summarizer.base.JobProgressService.reclassifyKey(userId);
        progress.start(key, "Neu kategorisieren", all.size());
        architect.resetBudget(userId);
        snapshotCategories(userId);

        float threshold = architect.threshold();
        int changed = 0;
        int skippedManual = 0;
        int done = 0;
        List<Item> waitlist = new ArrayList<>();

        // Pass 1: normale Zuordnung gegen den aktuellen Baum
        for (Item item : all) {
            boolean isFavorite = item.getCategoryId() != null
                    && favoriteIds.contains(item.getCategoryId());
            boolean isManual = keepManual && item.getCategoryConfidence() != null
                    && item.getCategoryConfidence() >= 0.999f;
            if (isFavorite || isManual) {
                if (isManual) {
                    skippedManual++;
                }
                autoTag(item);
                progress.update(key, ++done);
                continue;
            }
            Long before = item.getCategoryId();
            item.setCategoryId(null);
            item.setCategoryConfidence(null);
            // Stufe 0: Tag-Voting (kein LLM) — nur bei klarem Sieger, sonst LLM
            var voted = tagVoting.vote(userId, item.getId(), favoriteIds);
            if (voted.isPresent()) {
                item.setCategoryId(voted.get().categoryId());
                item.setCategoryConfidence(voted.get().confidence());
            } else {
                classify(item);
            }
            if (item.getCategoryId() == null
                    || item.getCategoryConfidence() == null
                    || item.getCategoryConfidence() < threshold) {
                waitlist.add(item);   // Pass 2 entscheidet
            }
            items.save(item);
            if (!java.util.Objects.equals(before, item.getCategoryId())) {
                changed++;
            }
            autoTag(item);
            progress.update(key, ++done);
        }

        // Pass 2: Architekt für die Warteliste — neue Kategorien stehen
        // nachfolgenden Items sofort zur Verfügung
        int architectPlaced = 0;
        if (allowNew && architect.enabled() && !waitlist.isEmpty()) {
            for (Item item : waitlist) {
                List<Category> current = categories
                        .findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                        .filter(c -> !favoriteIds.contains(c.getId()))
                        .toList();
                var placed = architect.place(userId, item.getTitle(), item.getSummary(),
                        item.getRawText(), current);
                if (placed.isPresent()) {
                    item.setCategoryId(placed.get().getId());
                    item.setCategoryConfidence(threshold);
                    items.save(item);
                    architectPlaced++;
                }
            }
        }

        long unsorted = waitlist.size() - architectPlaced;
        String result = changed + " umsortiert · " + architect.createdCount(userId)
                + " neue Kategorien · " + unsorted + " in Unsortiert"
                + (skippedManual > 0 ? " · " + skippedManual + " manuell unverändert" : "");
        progress.finish(key, result);
        com.summarizer.base.UiNotifier.broadcast("🗂 Neu kategorisiert: " + result);
        log.info("Neu-Kategorisierung für User {}: {}", userId, result);
    }

    /** Kategorien-Schnappschuss vor dem Lauf — Rollback-Material im Datenverzeichnis. */
    private void snapshotCategories(Long userId) {
        try {
            List<Category> all = categories.findByUserIdOrderBySortOrderAscNameAsc(userId);
            var mapper = new tools.jackson.databind.ObjectMapper();
            Path dir = filesDir.resolve("backups");
            java.nio.file.Files.createDirectories(dir);
            Path target = dir.resolve("categories-" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Category c : all) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("name", c.getName());
                row.put("description", c.getDescription());
                row.put("color", c.getColor());
                row.put("parentId", c.getParentId());
                row.put("id", c.getId());
                rows.add(row);
            }
            java.nio.file.Files.writeString(target, mapper.writeValueAsString(rows));
            log.info("Kategorien-Schnappschuss: {}", target);
        } catch (Exception e) {
            log.warn("Kategorien-Schnappschuss fehlgeschlagen: {}", e.getMessage());
        }
    }

    /**
     * Auto-Tags: 2-5 kurze Schlagworte per LLM. Bevorzugt bereits vorhandene Tags
     * des Users (konsistentes Vokabular); manuell vergebene Tags werden nie überschrieben.
     */
    private void autoTag(Item item) {
        if (!tags.tagsForItem(item.getId()).isEmpty()) {
            return;
        }
        String text = (item.getTitle() == null ? "" : item.getTitle() + "\n")
                + (item.getSummary() == null ? "" : item.getSummary() + "\n")
                + (item.getRawText() == null ? "" : item.getRawText());
        if (text.isBlank()) {
            return;
        }
        List<String> existing = tags.allTagNames(item.getUserId());
        String prompt = """
                Vergib 2 bis 5 kurze Schlagworte (Tags) für den folgenden Inhalt.

                Regeln:
                - Tags beschreiben, WORUM ES GEHT: Themen, Gegenstände, Orte, Personen, Konzepte.
                - VERBOTEN sind Medium/Format/Quelle als Tag (falsch: bild, foto, video, audio,
                  sprachnotiz, telegram, datei, pdf, dokument, webseite, text, screenshot).
                - Keine wörtlichen Code- oder Befehlsfragmente — stattdessen das Oberthema.
                - Kleinschreibung, 1-2 Wörter pro Tag, Deutsch.
                - Bevorzuge Tags aus dieser vorhandenen Liste, wenn sie inhaltlich passen: %s

                Beispiele:
                - Artikel über Docker-Healthchecks -> docker, devops, monitoring
                - Foto einer Küche mit Pizzaofen -> küche, pizzaofen, inneneinrichtung
                - Sprachnotiz über Elektriker-Termin -> handwerker, termin, solaranlage

                Antworte NUR mit den Tags, kommagetrennt, keine Erklärung.
                %s

                Inhalt:
                %s
                """.formatted(existing.isEmpty() ? "(noch keine vorhanden)" : String.join(", ", existing),
                com.summarizer.ai.PromptSanitizer.GUARD_NOTE,
                com.summarizer.ai.PromptSanitizer.wrapUntrusted(text, 3000));
        String answer = llm.generate(prompt,
                Map.of("type", "object",
                        "properties", Map.of("tags", Map.of("type", "array",
                                "items", Map.of("type", "string"), "maxItems", 5)),
                        "required", List.of("tags")));
        if (answer == null || answer.isBlank()) {
            return;
        }
        try {
            var node = new tools.jackson.databind.ObjectMapper().readTree(answer);
            if (node.has("tags") && node.get("tags").isArray()) {
                StringBuilder csv = new StringBuilder();
                node.get("tags").forEach(tag -> {
                    if (!csv.isEmpty()) {
                        csv.append(',');
                    }
                    csv.append(tag.asText());
                });
                applyTagLine(item, csv.toString());
            }
        } catch (Exception e) {
            log.debug("Item {}: Tag-JSON nicht lesbar", item.getId());
        }
    }

    private void classify(Item item) {
        // Favoriten-Teilbaum wird nur manuell befüllt — nicht automatisch klassifizieren
        List<Long> favoriteIds = favorites.subtreeIds(item.getUserId());
        List<Category> userCategories = categories.findByUserIdOrderBySortOrderAscNameAsc(item.getUserId())
                .stream()
                .filter(c -> !favoriteIds.contains(c.getId()))
                .toList();
        classification.classify(item.getTitle(), item.getRawText(), userCategories)
                .ifPresent(result -> {
                    item.setCategoryId(result.category().getId());
                    item.setCategoryConfidence(result.confidence());
                });
    }

    private void vectorize(Item item) {
        StringBuilder text = new StringBuilder();
        if (item.getTitle() != null) {
            text.append(item.getTitle()).append('\n');
        }
        if (item.getSummary() != null) {
            text.append(item.getSummary()).append('\n');
        }
        if (item.getRawText() != null) {
            text.append(item.getRawText());
        }
        int chunks = embeddings.embedAndStore(item.getId(), text.toString());
        log.info("Item {}: {} Chunks vektorisiert", item.getId(), chunks);
    }

    private String shorten(String message) {
        return message == null ? null : (message.length() > 500 ? message.substring(0, 500) : message);
    }
}
