package com.summarizer.ai;

import org.springframework.stereotype.Service;

/**
 * Zugriff auf das lokale LLM. Bewusst ohne Cloud-Provider und ohne API-Keys —
 * alle Inhalte bleiben auf dem eigenen Rechner.
 */
@Service
public class LlmRouter {

    private final OllamaClient ollama;

    public LlmRouter(OllamaClient ollama) {
        this.ollama = ollama;
    }

    public String generate(String prompt) {
        return ollama.generate(prompt);
    }

    /** Streaming-Antwort; onToken bekommt jedes Teilstück. */
    public String generateStreaming(String prompt, java.util.function.Consumer<String> onToken) {
        return ollama.generateStream(prompt, onToken);
    }


}
