package com.summarizer.item.pipeline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Schätzt die Restdauer der Pipeline-Warteschlange aus dem gemessenen
 * Durchsatz (fertige Items pro Minute, geglättet per EMA). Ein zentraler
 * Sampler statt Messung pro UI-Sitzung — alle Sessions teilen dieselbe Rate.
 */
@Service
public class PipelineEtaService {

    private final JdbcTemplate jdbc;
    private long lastDoneCount = -1;
    private long lastSampleMillis = 0;
    /** Items pro Minute, exponentiell geglättet; 0 = noch keine belastbare Rate. */
    private double perMinute = 0;

    public PipelineEtaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public synchronized void sample() {
        try {
            Long done = jdbc.queryForObject(
                    "SELECT count(*) FROM items WHERE status = 'DONE'", Long.class);
            long now = System.currentTimeMillis();
            if (done == null) {
                return;
            }
            if (lastDoneCount >= 0 && now > lastSampleMillis) {
                if (done > lastDoneCount) {
                    double rate = (done - lastDoneCount) * 60000.0 / (now - lastSampleMillis);
                    perMinute = perMinute <= 0 ? rate : 0.6 * perMinute + 0.4 * rate;
                } else {
                    // Kein Fortschritt im Fenster (Ollama down, Pause) — Rate abklingen
                    // lassen, damit keine veraltete Schätzung stehen bleibt
                    perMinute *= 0.5;
                }
            }
            lastDoneCount = done;
            lastSampleMillis = now;
        } catch (Exception ignored) {
            // DB kurz weg — nächster Lauf misst weiter
        }
    }

    /** Geschätzte Restdauer für queueSize wartende Items, leer ohne belastbare Rate. */
    public synchronized Optional<Duration> remaining(int queueSize) {
        if (queueSize <= 0 || perMinute < 0.05) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(Math.round(queueSize / perMinute * 60)));
    }
}
