package com.summarizer.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Client für den Whisper-ASR-Webservice (openai-whisper-asr-webservice):
 * POST /asr?output=json mit Audiodatei → Transkript.
 */
@Component
public class WhisperClient {

    private static final Logger log = LoggerFactory.getLogger(WhisperClient.class);

    private final RestClient restClient;

    public WhisperClient(@Value("${summarizer.whisper.base-url:http://localhost:9000}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofMinutes(15));   // lange Aufnahmen + Modell-Erststart
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public boolean isAvailable() {
        try {
            restClient.get().uri("/docs").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Transkribiert eine Audiodatei (Sprache automatisch erkannt). */
    public Optional<String> transcribe(Path audioFile) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("audio_file", new FileSystemResource(audioFile));
            // Service liefert JSON mit Content-Type text/plain -> als String holen, selbst parsen
            String raw = restClient.post()
                    .uri("/asr?task=transcribe&encode=true&output=json")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            var node = new tools.jackson.databind.ObjectMapper().readTree(raw);
            String text = node.path("text").asString("");
            return text.isBlank() ? Optional.empty() : Optional.of(text.strip());
        } catch (Exception e) {
            log.warn("Whisper-Transkription fehlgeschlagen: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
