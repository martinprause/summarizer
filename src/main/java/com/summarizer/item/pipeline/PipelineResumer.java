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

    public PipelineResumer(JdbcTemplate jdbc, IngestPipeline pipeline,
                           com.summarizer.ai.OllamaClient ollama) {
        this.jdbc = jdbc;
        this.pipeline = pipeline;
        this.ollama = ollama;
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
}
