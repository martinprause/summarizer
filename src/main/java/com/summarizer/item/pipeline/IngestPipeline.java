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
import java.util.List;
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

    public IngestPipeline(ItemRepository items, CategoryRepository categories,
                          FavoritesService favorites, WebPageExtractor extractor,
                          com.summarizer.item.extract.FileTextExtractor fileExtractor,
                          ClassificationService classification, EmbeddingService embeddings,
                          GraphExtractionService graphExtraction, LlmRouter llm,
                          OllamaClient ollama, com.summarizer.ai.WhisperClient whisper,
                          com.summarizer.item.TagService tags,
                          com.summarizer.base.JobProgressService progress,
                          @Value("${summarizer.files.dir}") String filesDir) {
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

        String prompt = """
                Analysiere den folgenden Inhalt und antworte mit GENAU drei Zeilen,
                jede beginnt mit ihrem Schlüsselwort:

                SUMMARY|Zusammenfassung in 2-3 prägnanten deutschen Sätzen (kein Präfix, keine Einleitung)
                CATEGORY|Kategoriepfad|Konfidenz zwischen 0 und 1
                TAGS|2 bis 5 kleingeschriebene schlagworte, kommagetrennt

                Kategorien (hierarchisch, "Eltern > Kind" — Pfad EXAKT übernehmen,
                wähle die SPEZIFISCHSTE passende):
                %s
                Regeln für Tags:
                - Tags beschreiben, WORUM ES GEHT (Themen, Orte, Personen, Konzepte).
                - VERBOTEN: Medium/Format/Quelle (bild, video, pdf, telegram, webseite ...).
                - Bevorzuge vorhandene Tags, wenn sie passen: %s

                %s

                Inhalt:
                %s
                """.formatted(
                userCategories.isEmpty() ? "(keine Kategorien definiert)"
                        : classification.categoryListing(userCategories),
                existingTags.isEmpty() ? "(noch keine vorhanden)" : String.join(", ", existingTags),
                com.summarizer.ai.PromptSanitizer.GUARD_NOTE,
                com.summarizer.ai.PromptSanitizer.wrapUntrusted(text, 5000));

        String answer = llm.generate(prompt);
        String summaryLine = null;
        String categoryLine = null;
        String tagLine = null;
        if (answer != null) {
            for (String line : answer.strip().lines().toList()) {
                String l = line.strip();
                if (l.regionMatches(true, 0, "SUMMARY|", 0, 8)) {
                    summaryLine = l.substring(8).strip();
                } else if (l.regionMatches(true, 0, "CATEGORY|", 0, 9)) {
                    categoryLine = l.substring(9).strip();
                } else if (l.regionMatches(true, 0, "TAGS|", 0, 5)) {
                    tagLine = l.substring(5).strip();
                }
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
    }

    /** Kommagetrennte Tag-Zeile filtern und setzen (gemeinsame Regeln mit autoTag). */
    private void applyTagLine(Item item, String csv) {
        List<String> parsed = java.util.Arrays.stream(csv.split(","))
                .map(t -> t.strip().toLowerCase()
                        .replaceAll("^#", "")
                        .replaceAll("[\"'.]", ""))
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
            "text", "screenshot", "notiz", "aufnahme", "upload", "link", "bookmark");

    /** 2-3-Sätze-Zusammenfassung — der Namensgeber der App. */
    private void summarize(Item item) {
        String text = item.getRawText();
        if (text == null || text.isBlank() || text.length() < 200) {
            return;   // Kurztexte brauchen keine Zusammenfassung
        }
        String summary = llm.generate("""
                Fasse den folgenden Inhalt in 2-3 prägnanten deutschen Sätzen zusammen.
                Nur die Zusammenfassung, keine Einleitung.

                %s

                %s
                """.formatted(com.summarizer.ai.PromptSanitizer.GUARD_NOTE,
                com.summarizer.ai.PromptSanitizer.wrapUntrusted(text, 6000)));
        if (summary != null && !summary.isBlank()) {
            item.setSummary(summary.strip());
        }
    }

    /**
     * Alle fertigen Items neu klassifizieren — z. B. nach Umbau der Kategorien.
     * Items im Favoriten-Teilbaum bleiben unangetastet (nur manuell gepflegt).
     */
    @Async
    public void reclassifyAll(Long userId) {
        List<Long> favoriteIds = favorites.subtreeIds(userId);
        List<Item> all = items.findByUserIdAndStatus(userId, Item.Status.DONE);
        String key = com.summarizer.base.JobProgressService.reclassifyKey(userId);
        progress.start(key, "Neu kategorisieren", all.size());
        int changed = 0;
        int done = 0;
        for (Item item : all) {
            if (item.getCategoryId() == null || !favoriteIds.contains(item.getCategoryId())) {
                Long before = item.getCategoryId();
                item.setCategoryId(null);
                item.setCategoryConfidence(null);
                classify(item);
                items.save(item);
                if (!java.util.Objects.equals(before, item.getCategoryId())) {
                    changed++;
                }
            }
            autoTag(item);   // vergibt Tags nur, wo noch keine sind
            progress.update(key, ++done);
        }
        progress.finish(key, changed + " von " + all.size() + " Items umsortiert");
        log.info("Neu-Kategorisierung für User {}: {} Items geprüft, {} umsortiert",
                userId, all.size(), changed);
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
        String answer = llm.generate(prompt);
        if (answer == null || answer.isBlank()) {
            return;
        }
        applyTagLine(item, answer.strip().lines().findFirst().orElse(""));
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
