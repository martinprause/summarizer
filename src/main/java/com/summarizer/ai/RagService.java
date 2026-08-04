package com.summarizer.ai;

import com.summarizer.graph.GraphService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * "Frage dein Archiv": Hybrid-Retrieval — pgvector-Chunks plus Wissensgraph-Fakten
 * (GraphRAG: Entitäten aus der Frage → 1-Hop-Nachbarschaft als Zusatzkontext).
 */
@Service
public class RagService {

    private static final int TOP_K = 6;
    private static final int MAX_CHUNK_CHARS = 1500;

    private final EmbeddingService embeddings;
    private final LlmRouter llm;
    private final GraphService graph;

    public RagService(EmbeddingService embeddings, LlmRouter llm, GraphService graph) {
        this.embeddings = embeddings;
        this.llm = llm;
        this.graph = graph;
    }

    /** Retrieval + Prompt-Bau, ohne Generierung — für den Streaming-Chat. */
    public Prompt buildPrompt(Long userId, String question) {
        return buildPrompt(userId, question, TOP_K);
    }

    /** Wie oben, mit konfigurierbarer Trefferanzahl (Chat-Header). */
    public Prompt buildPrompt(Long userId, String question, int topK) {
        List<EmbeddingService.SearchHit> hits = embeddings.search(userId, question, Math.clamp(topK, 1, 20));
        if (hits.isEmpty()) {
            return new Prompt(null, List.of());
        }
        String context = buildContext(hits);
        String prompt = """
                Du beantwortest Fragen aus einem persönlichen Wissensarchiv. Nutze \
                AUSSCHLIESSLICH die folgenden Auszüge und Wissensgraph-Fakten.

                FORMAT — NUR RESULTATE:
                - Antworte als Markdown-Stichpunkte, jeder Stichpunkt EINE Kernaussage
                  mit Quellenverweis [1], [2] am Ende.
                - KEINE Einleitung, KEIN Schlusssatz, KEINE Sätze über das Archiv selbst.
                - VERBOTEN sind Meta-Sätze wie "Für Informationen zu X siehe [2]" oder
                  "Dazu findet sich nichts über Y" — lasse Fehlendes einfach weg.
                - Nur wenn KEIN Auszug thematisch passt, antworte mit genau einem Satz:
                  "Dazu findet sich nichts im Archiv."
                - Ist die Frage nur ein Stichwort, liste die Kernaussagen des Archivs
                  zu diesem Thema — kopiere NIE Auszüge wörtlich.

                %s

                Auszüge:
                %s
                %s
                Frage: %s

                Antwort (auf Deutsch, nur Stichpunkte):
                """.formatted(PromptSanitizer.GUARD_NOTE,
                PromptSanitizer.wrapUntrusted(context, 30_000),
                buildGraphContext(userId, question), question);
        return new Prompt(prompt, hits);
    }

    /** Auszüge: Titel + Zusammenfassung (Kernaussage!) + bester Chunk je Quelle. */
    private String buildContext(List<EmbeddingService.SearchHit> hits) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            EmbeddingService.SearchHit hit = hits.get(i);
            String chunk = hit.chunkText();
            if (chunk.length() > MAX_CHUNK_CHARS) {
                chunk = chunk.substring(0, MAX_CHUNK_CHARS);
            }
            context.append("[").append(i + 1).append("] ")
                    .append(hit.title() == null ? "(ohne Titel)" : hit.title()).append(":\n");
            if (hit.summary() != null && !hit.summary().isBlank()) {
                context.append("Zusammenfassung: ").append(hit.summary()).append('\n');
            }
            context.append(chunk).append("\n\n");
        }
        return context.toString();
    }

    public record Prompt(String text, List<EmbeddingService.SearchHit> sources) {
    }

    public Answer ask(Long userId, String question) {
        List<EmbeddingService.SearchHit> hits = embeddings.search(userId, question, TOP_K);
        if (hits.isEmpty()) {
            return new Answer("Dazu finde ich nichts in deinem Archiv — entweder sind noch keine "
                    + "passenden Inhalte gespeichert oder Ollama ist nicht erreichbar.", List.of());
        }

        String context = buildContext(hits);
        String graphContext = buildGraphContext(userId, question);

        String prompt = """
                Du beantwortest Fragen aus einem persönlichen Wissensarchiv. Nutze \
                AUSSCHLIESSLICH die folgenden Auszüge und Wissensgraph-Fakten.

                FORMAT — NUR RESULTATE:
                - Antworte als Stichpunkte, jeder Stichpunkt EINE Kernaussage
                  mit Quellenverweis [1], [2] am Ende.
                - KEINE Einleitung, KEIN Schlusssatz, KEINE Sätze über das Archiv selbst.
                - VERBOTEN sind Meta-Sätze wie "Für Informationen zu X siehe [2]" oder
                  "Dazu findet sich nichts über Y" — lasse Fehlendes einfach weg.
                - Nur wenn KEIN Auszug thematisch passt, antworte mit genau einem Satz:
                  "Dazu findet sich nichts im Archiv."

                Auszüge:
                %s%s
                Frage: %s

                Antwort (auf Deutsch, nur Stichpunkte):
                """.formatted(context, graphContext, question);

        String answer = llm.generate(prompt);
        if (answer == null || answer.isBlank()) {
            return new Answer("Das LLM ist gerade nicht erreichbar — bitte später erneut versuchen.", hits);
        }
        return new Answer(answer.strip(), hits);
    }

    /** GraphRAG-Teil: Entitäten aus der Frage finden, 1-Hop-Beziehungen als Fakten anhängen. */
    private String buildGraphContext(Long userId, String question) {
        List<GraphService.Entity> matched = graph.findEntitiesInText(userId, question);
        if (matched.isEmpty()) {
            return "";
        }
        List<Long> ids = matched.stream().map(GraphService.Entity::id).toList();
        StringBuilder sb = new StringBuilder("Wissensgraph-Fakten:\n");
        for (GraphService.Entity entity : matched) {
            sb.append("- ").append(entity.name());
            if (entity.description() != null && !entity.description().isBlank()) {
                sb.append(": ").append(entity.description());
            }
            sb.append('\n');
        }
        for (GraphService.Relation relation : graph.neighborhood(userId, ids)) {
            sb.append("- ").append(relation.sourceName()).append(" —")
                    .append(relation.relation() == null ? "verbunden mit" : relation.relation())
                    .append("→ ").append(relation.targetName()).append('\n');
        }
        return sb.append('\n').toString();
    }

    public record Answer(String text, List<EmbeddingService.SearchHit> sources) {
    }
}
