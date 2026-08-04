package com.summarizer.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Schlanker Client für die Ollama-REST-API (Chat/Generate + Embeddings).
 * Bewusst ohne Spring AI, um Boot-4-Kompatibilität nicht zu riskieren;
 * die Abstraktion erlaubt später einen Cloud-LLM-Provider als Alternative.
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestClient restClient;
    private final RestClient longRunningClient;   // fuer Modell-Downloads (Stunden-Timeout)
    private final String baseUrl;
    private final String configuredChatModel;
    private final String configuredEmbeddingModel;
    private final String visionModel;
    private final com.summarizer.settings.AppSettingsService settings;

    public static final String CHAT_MODEL_KEY = "ollama.chat-model";
    public static final String EMBEDDING_MODEL_KEY = "ollama.embedding-model";

    public OllamaClient(@Value("${summarizer.ollama.base-url}") String baseUrl,
                        @Value("${summarizer.ollama.chat-model}") String chatModel,
                        @Value("${summarizer.ollama.embedding-model}") String embeddingModel,
                        @Value("${summarizer.ollama.vision-model:llava:7b}") String visionModel,
                        com.summarizer.settings.AppSettingsService settings) {
        this.baseUrl = baseUrl;
        this.visionModel = visionModel;
        this.settings = settings;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(180));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        SimpleClientHttpRequestFactory longFactory = new SimpleClientHttpRequestFactory();
        longFactory.setConnectTimeout(Duration.ofSeconds(5));
        longFactory.setReadTimeout(Duration.ofHours(2));
        this.longRunningClient = RestClient.builder().baseUrl(baseUrl).requestFactory(longFactory).build();
        this.configuredChatModel = chatModel;
        this.configuredEmbeddingModel = embeddingModel;
    }

    /** Aktives Chat-Modell — im Studio waehlbar, sonst aus der Konfiguration. */
    public String chatModel() {
        return settings.get(CHAT_MODEL_KEY, configuredChatModel);
    }

    /** Aktives Embedding-Modell — im Studio waehlbar, sonst aus der Konfiguration. */
    public String embeddingModel() {
        return settings.get(EMBEDDING_MODEL_KEY, configuredEmbeddingModel);
    }

    public boolean isAvailable() {
        try {
            restClient.get().uri("/api/tags").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Ollama nicht erreichbar: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Max. 2 gleichzeitige Generierungen: mehr parallele Anfragen bringen auf
     * CPU nichts und haben Ollama unter Last zum Absturz gebracht (alle
     * Verbindungen gleichzeitig gekappt). Die Pipeline-Worker parallelisieren
     * weiterhin Webseiten-Abrufe — nur der LLM-Teil wird hier serialisiert.
     */
    private static final java.util.concurrent.Semaphore GENERATE_SLOTS =
            new java.util.concurrent.Semaphore(1, true);

    /** Einzelner Prompt, komplette Antwort (kein Streaming). Null bei Fehler. */
    public String generate(String prompt) {
        return generate(prompt, null);
    }

    /**
     * Structured Output: format = JSON-Schema (Ollama erzwingt dann valides JSON
     * nach diesem Schema — kein Format-Nachplappern mehr möglich).
     */
    public String generate(String prompt, Map<String, Object> format) {
        try {
            GENERATE_SLOTS.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            java.util.HashMap<String, Object> body = new java.util.HashMap<>();
            body.put("model", chatModel());
            body.put("prompt", prompt);
            body.put("stream", false);
            body.put("options", Map.of("temperature", 0));
            if (format != null) {
                body.put("format", format);
            }
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    Map<?, ?> response = restClient.post()
                            .uri("/api/generate")
                            .body(body)
                            .retrieve()
                            .body(Map.class);
                    return response == null ? null : (String) response.get("response");
                } catch (Exception e) {
                    log.warn("Ollama generate fehlgeschlagen (Versuch {}): {}", attempt, e.getMessage());
                    if (attempt == 1) {
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    }
                }
            }
            return null;
        } finally {
            GENERATE_SLOTS.release();
        }
    }

    /** Bildbeschreibung/OCR über das Vision-Modell (Bild als Base64). Null bei Fehler. */
    public String generateWithImage(String prompt, byte[] image) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/api/generate")
                    .body(Map.of(
                            "model", visionModel,
                            "prompt", prompt,
                            "images", List.of(java.util.Base64.getEncoder().encodeToString(image)),
                            "stream", false,
                            "options", Map.of("temperature", 0)))
                    .retrieve()
                    .body(Map.class);
            return response == null ? null : (String) response.get("response");
        } catch (Exception e) {
            log.warn("Ollama Vision fehlgeschlagen ({}): {}", visionModel, e.getMessage());
            return null;
        }
    }

    /**
     * Streaming-Generierung: onToken wird pro Teilstück aufgerufen.
     * Rückgabe = komplette Antwort (null bei Fehler).
     */
    public String generateStream(String prompt, java.util.function.Consumer<String> onToken) {
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            var httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            String body = mapper.writeValueAsString(Map.of(
                    "model", chatModel(), "prompt", prompt, "stream", true,
                    "options", Map.of("temperature", 0)));
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(10))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var response = httpClient.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofLines());
            StringBuilder full = new StringBuilder();
            response.body().forEach(line -> {
                if (line.isBlank()) {
                    return;
                }
                var node = mapper.readTree(line);
                String token = node.path("response").asString("");
                if (!token.isEmpty()) {
                    full.append(token);
                    onToken.accept(token);
                }
            });
            return full.toString();
        } catch (Exception e) {
            log.warn("Ollama Streaming fehlgeschlagen: {}", e.getMessage());
            return null;
        }
    }

    /** Installierte Modelle (GET /api/tags). */
    @SuppressWarnings("unchecked")
    public List<ModelInfo> listModels() {
        try {
            Map<?, ?> response = restClient.get().uri("/api/tags").retrieve().body(Map.class);
            if (response == null || response.get("models") == null) {
                return List.of();
            }
            return ((List<Map<String, Object>>) response.get("models")).stream()
                    .map(m -> new ModelInfo((String) m.get("name"),
                            m.get("size") instanceof Number n ? n.longValue() : 0))
                    .toList();
        } catch (Exception e) {
            log.warn("Ollama listModels fehlgeschlagen: {}", e.getMessage());
            return List.of();
        }
    }

    /** Modell herunterladen (POST /api/pull, blockierend — im Async-Kontext aufrufen). */
    public void pull(String model) {
        longRunningClient.post().uri("/api/pull")
                .body(Map.of("model", model, "stream", false))
                .retrieve().toBodilessEntity();
    }

    /** Modell löschen (DELETE /api/delete). */
    public void deleteModel(String model) {
        restClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/api/delete")
                .body(Map.of("model", model))
                .retrieve().toBodilessEntity();
    }

    public record ModelInfo(String name, long sizeBytes) {
    }

    /** Embeddings für mehrere Texte. Leere Liste bei Fehler. */
    @SuppressWarnings("unchecked")
    public List<List<Double>> embed(List<String> inputs) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/api/embed")
                    .body(Map.of("model", embeddingModel(), "input", inputs))
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                return List.of();
            }
            return (List<List<Double>>) response.get("embeddings");
        } catch (Exception e) {
            log.warn("Ollama embed fehlgeschlagen: {}", e.getMessage());
            return List.of();
        }
    }
}
