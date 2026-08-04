package com.summarizer.item.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Setzt beim App-Start liegengebliebene Verarbeitung fort: Inhalte, die beim
 * letzten Lauf noch PENDING waren oder mitten in PROCESSING abgebrochen wurden
 * (Absturz, Docker down), laufen automatisch erneut durch die Pipeline.
 * Der Fortschritt ist in der Statusleiste sichtbar ("in Verarbeitung").
 */
@Component
public class PipelineResumer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineResumer.class);

    private final JdbcTemplate jdbc;
    private final IngestPipeline pipeline;
    private final com.summarizer.ai.OllamaClient ollama;
    private final com.summarizer.graph.GraphExtractionService graphExtraction;
    private final com.summarizer.item.ItemRepository items;
    private final java.util.Set<Long> graphBackfillTried =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PipelineResumer(JdbcTemplate jdbc, IngestPipeline pipeline,
                           com.summarizer.ai.OllamaClient ollama,
                           com.summarizer.graph.GraphExtractionService graphExtraction,
                           com.summarizer.item.ItemRepository items) {
        this.jdbc = jdbc;
        this.pipeline = pipeline;
        this.ollama = ollama;
        this.graphExtraction = graphExtraction;
        this.items = items;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // Abgebrochene PROCESSING-Items zuruecksetzen, dann alles Offene anstossen
            jdbc.update("UPDATE items SET status = 'PENDING' WHERE status = 'PROCESSING'");
            List<Long> pending = jdbc.queryForList(
                    "SELECT id FROM items WHERE status = 'PENDING' ORDER BY id", Long.class);
            if (pending.isEmpty()) {
                return;
            }
            log.info("Setze Verarbeitung fort: {} unverarbeitete Inhalte aus dem letzten Lauf",
                    pending.size());
            pending.forEach(pipeline::process);
        } catch (Exception e) {
            log.warn("Wiederaufnahme der Pipeline fehlgeschlagen: {}", e.getMessage());
        }
    }

    /**
     * Zurückgestellte Inhalte regelmäßig nachholen — greift, wenn Ollama beim
     * ersten Versuch nicht erreichbar war (z. B. Modelle noch im Download).
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 120000, initialDelay = 120000)
    public void retryPending() {
        try {
            if (!ollama.isAvailable()) {
                return;
            }
            List<Long> pending = jdbc.queryForList(
                    "SELECT id FROM items WHERE status = 'PENDING' ORDER BY id", Long.class);
            if (pending.isEmpty()) {
                return;
            }
            log.info("Nachverarbeitung: {} wartende Inhalte", pending.size());
            pending.forEach(pipeline::process);
        } catch (Exception ignored) {
            // DB kurz weg — nächster Lauf versucht es erneut
        }
    }

    /**
     * Graph-Backfill: Beim Massen-Import wird die Graph-Extraktion übersprungen.
     * Sobald kein Import mehr läuft, holt dieser Job sie schubweise nach —
     * fertige Items ohne Graph-Verknüpfung erkennt man an fehlenden item_entities.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 180000, initialDelay = 90000)
    public void backfillGraph() {
        try {
            if (!ollama.isAvailable()) {
                return;
            }
            if (items.countByStatus(com.summarizer.item.Item.Status.PENDING) > 0) {
                return;   // Import läuft noch — nicht dazwischenfunken
            }
            List<Long> missing = jdbc.queryForList("""
                    SELECT i.id FROM items i
                    WHERE i.status = 'DONE'
                      AND NOT EXISTS (SELECT 1 FROM item_entities ie WHERE ie.item_id = i.id)
                    ORDER BY i.id
                    LIMIT 40
                    """, Long.class);
            // Bereits versuchte Items nicht endlos wiederholen (z. B. Inhalte,
            // aus denen das LLM schlicht keine Entitäten zieht)
            missing = missing.stream().filter(id -> !graphBackfillTried.contains(id)).toList();
            if (missing.isEmpty()) {
                return;
            }
            log.info("Graph-Backfill: {} Inhalte ohne Graph-Verknüpfung", missing.size());
            for (Long id : missing) {
                graphBackfillTried.add(id);
                items.findById(id).ifPresent(item -> {
                    try {
                        graphExtraction.extract(item);
                    } catch (Exception e) {
                        log.debug("Graph-Backfill für Item {} fehlgeschlagen: {}", id, e.getMessage());
                    }
                });
            }
        } catch (Exception ignored) {
            // nächster Lauf versucht es erneut
        }
    }
}
