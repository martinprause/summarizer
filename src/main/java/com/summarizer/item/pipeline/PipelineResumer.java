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

    public PipelineResumer(JdbcTemplate jdbc, IngestPipeline pipeline) {
        this.jdbc = jdbc;
        this.pipeline = pipeline;
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
}
