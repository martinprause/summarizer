package com.summarizer.ai;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Liest die verfügbaren Modelle direkt aus der Ollama-Bibliothek
 * (ollama.com/library) statt aus einer fest verdrahteten Liste.
 * Ergebnisse werden eine Stunde zwischengespeichert.
 */
@Service
public class OllamaLibraryService {

    private static final Logger log = LoggerFactory.getLogger(OllamaLibraryService.class);
    private static final String LIBRARY_URL = "https://ollama.com/library";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    /** Fallback, falls ollama.com nicht erreichbar ist (Offline-Installation). */
    private static final List<String> FALLBACK = List.of(
            "qwen3.5:4b", "qwen3.5:9b", "llama3.2:3b", "llama3.1:8b",
            "gemma3:4b", "mistral:7b", "llava:7b", "nomic-embed-text", "bge-m3");

    private List<String> cachedModels = List.of();
    private Instant cachedAt = Instant.EPOCH;

    /** Modellnamen ohne Tag, alphabetisch (z. B. "llama3.2", "qwen3.5"). */
    public synchronized List<String> availableModels() {
        if (!cachedModels.isEmpty() && cachedAt.plus(CACHE_TTL).isAfter(Instant.now())) {
            return cachedModels;
        }
        try {
            List<String> names = Jsoup.connect(LIBRARY_URL)
                    .userAgent("Mozilla/5.0 Summarizer/0.1")
                    .timeout(10_000)
                    .get()
                    .select("a[href^=/library/]")
                    .stream()
                    .map(a -> a.attr("href").substring("/library/".length()))
                    .filter(name -> !name.isBlank() && !name.contains("/"))
                    .distinct()
                    .sorted()
                    .toList();
            if (!names.isEmpty()) {
                cachedModels = names;
                cachedAt = Instant.now();
                log.info("Ollama-Bibliothek geladen: {} Modelle", names.size());
                return names;
            }
        } catch (Exception e) {
            log.warn("Ollama-Bibliothek nicht erreichbar ({}), nutze Fallback-Liste", e.getMessage());
        }
        return cachedModels.isEmpty() ? FALLBACK : cachedModels;
    }

    /** Verfügbare Größen/Tags eines Modells (z. B. "4b", "9b", "latest"). */
    public List<String> tagsFor(String model) {
        List<String> tags = new ArrayList<>();
        try {
            // Tags stehen als "modell:tag" im Seitentext
            String html = Jsoup.connect(LIBRARY_URL + "/" + model + "/tags")
                    .userAgent("Mozilla/5.0 Summarizer/0.1")
                    .timeout(10_000)
                    .get()
                    .text();
            var matcher = java.util.regex.Pattern
                    .compile(java.util.regex.Pattern.quote(model) + ":([a-zA-Z0-9._-]+)")
                    .matcher(html);
            while (matcher.find()) {
                String tag = matcher.group(1);
                // Quantisierungs-Varianten ausblenden — für die Auswahl zu speziell
                if (!tags.contains(tag) && !tag.matches(".*(mlx|bf16|fp16|fp8|nvfp4|mxfp8|q\\d).*")) {
                    tags.add(tag);
                }
            }
            tags.sort(java.util.Comparator.comparing((String t) -> !t.equals("latest"))
                    .thenComparing(t -> t));
        } catch (Exception e) {
            log.warn("Tags für {} nicht abrufbar: {}", model, e.getMessage());
        }
        if (tags.isEmpty()) {
            tags.add("latest");
        }
        return tags;
    }
}
