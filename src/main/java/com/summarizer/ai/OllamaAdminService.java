package com.summarizer.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modell-Downloads im Hintergrund mit einfachem Status-Tracking für die UI.
 */
@Service
public class OllamaAdminService {

    private static final Logger log = LoggerFactory.getLogger(OllamaAdminService.class);

    private final OllamaClient ollama;
    private final Map<String, String> pullStatus = new ConcurrentHashMap<>();

    public OllamaAdminService(OllamaClient ollama) {
        this.ollama = ollama;
    }

    @Async
    public void pullAsync(String model) {
        pullStatus.put(model, "lädt …");
        try {
            ollama.pull(model);
            pullStatus.put(model, "fertig ✓");
            log.info("Modell {} heruntergeladen", model);
        } catch (Exception e) {
            pullStatus.put(model, "Fehler: " + e.getMessage());
            log.warn("Modell-Pull {} fehlgeschlagen: {}", model, e.getMessage());
        }
    }

    public Map<String, String> status() {
        return Map.copyOf(pullStatus);
    }

    public void clearStatus(String model) {
        pullStatus.remove(model);
    }
}
