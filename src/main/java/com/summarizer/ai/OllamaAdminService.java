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
    private final Map<String, Integer> pullPercent = new ConcurrentHashMap<>();

    public OllamaAdminService(OllamaClient ollama) {
        this.ollama = ollama;
    }

    @Async
    public void pullAsync(String model) {
        pullStatus.put(model, "lädt …");
        pullPercent.put(model, 0);
        try {
            ollama.pull(model, percent -> {
                pullPercent.put(model, percent);
                pullStatus.put(model, "lädt … " + percent + "%");
            });
            pullStatus.put(model, "fertig ✓");
            pullPercent.remove(model);
            log.info("Modell {} heruntergeladen", model);
        } catch (Exception e) {
            pullStatus.put(model, "Fehler: " + e.getMessage());
            pullPercent.remove(model);
            log.warn("Modell-Pull {} fehlgeschlagen: {}", model, e.getMessage());
        }
    }

    /** Download-Fortschritt 0-100, -1 wenn für das Modell kein Pull läuft. */
    public int percentOf(String model) {
        return pullPercent.getOrDefault(model, -1);
    }

    /**
     * Fehlende Pflicht-Modelle (Chat + Embedding) automatisch nachladen.
     * Deckt beide Setups ab: Container-Ollama, dessen init-Lauf scheiterte,
     * und ein bereits vorhandenes Host-Ollama ohne unsere Modelle.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300000, initialDelay = 15000)
    public void ensureRequiredModels() {
        try {
            if (!ollama.isAvailable()) {
                return;
            }
            java.util.List<String> installed = ollama.listModels().stream()
                    .map(OllamaClient.ModelInfo::name).toList();
            for (String required : java.util.List.of(ollama.chatModel(), ollama.embeddingModel())) {
                boolean present = installed.stream().anyMatch(name -> name.equals(required)
                        || name.equals(required + ":latest") || required.equals(name + ":latest"));
                boolean pulling = "lädt …".equals(pullStatus.get(required));
                if (!present && !pulling) {
                    log.info("Erforderliches Modell {} fehlt — Download startet automatisch", required);
                    pullAsync(required);
                }
            }
        } catch (Exception ignored) {
            // Ollama gerade nicht ansprechbar — nächster Lauf prüft erneut
        }
    }

    public Map<String, String> status() {
        return Map.copyOf(pullStatus);
    }

    public void clearStatus(String model) {
        pullStatus.remove(model);
    }
}
