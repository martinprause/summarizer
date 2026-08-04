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
                Du bist der Assistent eines persönlichen Wissensarchivs. Beantworte die Frage \
                AUSSCHLIESSLICH mit den folgenden Auszügen und Wissensgraph-Fakten aus dem Archiv. \
                Deckt ein Auszug das Thema nur teilweise ab, gib wieder, was das Archiv dazu \
                enthält, und nenne die Quelle — antworte nur dann mit "dazu findet sich nichts", \
                wenn KEIN Auszug thematisch passt. \
                Ist die Frage nur ein Stichwort, fasse in eigenen Worten zusammen, was das \
                Archiv zu diesem Thema enthält — kopiere NIE Auszüge wörtlich. \
                Verweise auf Quellen in der Form [1], [2]. Antworte in Markdown.

                %s

                Auszüge:
                %s
                %s
                Frage: %s

                Antwort (auf Deutsch, knapp und präzise):
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
                Du bist der Assistent eines persönlichen Wissensarchivs. Beantworte die Frage \
                AUSSCHLIESSLICH mit den folgenden Auszügen und Wissensgraph-Fakten aus dem Archiv. \
                Deckt ein Auszug das Thema nur teilweise ab, gib wieder, was das Archiv dazu \
                enthält, und nenne die Quelle — antworte nur dann mit "dazu findet sich nichts", \
                wenn KEIN Auszug thematisch passt. \
                Verweise auf Quellen in der Form [1], [2].

                Auszüge:
                %s%s
                Frage: %s

                Antwort (auf Deutsch, knapp und präzise):
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
