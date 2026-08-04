package com.summarizer.ai;

import com.summarizer.category.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ordnet Inhalte per LLM einer der User-Kategorien zu.
 * Hierarchie-Regel: Kategorien werden als Pfade präsentiert ("Technik > KI"),
 * das LLM soll die SPEZIFISCHSTE passende Kategorie wählen — Eltern nur,
 * wenn kein Kind passt.
 * Antwortformat des LLM: "Pfad|0.85" — bewusst kein JSON,
 * damit kleine Modelle das Format zuverlässig treffen.
 */
@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);
    private static final int MAX_CONTENT_CHARS = 4000;

    private final LlmRouter llm;

    public ClassificationService(LlmRouter llm) {
        this.llm = llm;
    }

    public Optional<Result> classify(String title, String content, List<Category> categories) {
        if (categories.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, String> paths = buildPaths(categories);
        // Structured Output: {category_path, confidence} — kein Format-Raten mehr
        String answer = llm.generate(buildPrompt(title, content, categories, paths),
                Map.of("type", "object",
                        "properties", Map.of(
                                "category_path", Map.of("type", "string"),
                                "confidence", Map.of("type", "number")),
                        "required", List.of("category_path", "confidence")));
        if (answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        try {
            var node = new tools.jackson.databind.ObjectMapper().readTree(answer);
            String line = node.path("category_path").asText("")
                    + "|" + node.path("confidence").asText("0.5");
            return parse(line, categories, paths);
        } catch (Exception e) {
            return parse(answer, categories, paths);   // Fallback: alte Zeilen-Logik
        }
    }

    /** Kategorienliste als Prompt-Baustein (für den kombinierten Pipeline-Aufruf). */
    public String categoryListing(List<Category> categories) {
        Map<Long, String> paths = buildPaths(categories);
        StringBuilder sb = new StringBuilder();
        categories.stream()
                .sorted((a, b) -> paths.get(a.getId()).compareToIgnoreCase(paths.get(b.getId())))
                .forEach(c -> {
                    sb.append("- ").append(paths.get(c.getId()));
                    if (c.getDescription() != null && !c.getDescription().isBlank()) {
                        sb.append(": ").append(c.getDescription());
                    }
                    sb.append('\n');
                });
        return sb.toString();
    }

    /** Eine "Pfad|Konfidenz"-Zeile gegen die Kategorien auflösen (kombinierter Aufruf). */
    public Optional<Result> matchLine(String line, List<Category> categories) {
        if (line == null || line.isBlank() || categories.isEmpty()) {
            return Optional.empty();
        }
        return parse(line, categories, buildPaths(categories));
    }

    /** Voller Pfad je Kategorie, z. B. "Technik > KI". */
    private Map<Long, String> buildPaths(List<Category> categories) {
        Map<Long, Category> byId = new HashMap<>();
        categories.forEach(c -> byId.put(c.getId(), c));
        Map<Long, String> paths = new HashMap<>();
        for (Category c : categories) {
            StringBuilder path = new StringBuilder(c.getName());
            Long parentId = c.getParentId();
            int guard = 0;
            while (parentId != null && byId.containsKey(parentId) && guard++ < 10) {
                Category parent = byId.get(parentId);
                path.insert(0, parent.getName() + " > ");
                parentId = parent.getParentId();
            }
            paths.put(c.getId(), path.toString());
        }
        return paths;
    }

    private String buildPrompt(String title, String content, List<Category> categories,
                               Map<Long, String> paths) {
        StringBuilder prompt = new StringBuilder("""
                Du bist ein Klassifikator. Ordne den folgenden Inhalt genau einer Kategorie zu.

                Kategorien (hierarchisch, "Eltern > Kind"):
                """);
        categories.stream()
                .sorted((a, b) -> paths.get(a.getId()).compareToIgnoreCase(paths.get(b.getId())))
                .forEach(c -> {
                    prompt.append("- ").append(paths.get(c.getId()));
                    if (c.getDescription() != null && !c.getDescription().isBlank()) {
                        prompt.append(": ").append(c.getDescription());
                    }
                    prompt.append('\n');
                });
        prompt.append("""

                REGEL: Wähle die SPEZIFISCHSTE passende Kategorie, also die tiefste Ebene,
                deren Beschreibung zutrifft. Eine übergeordnete Kategorie nur dann,
                wenn keine ihrer Unterkategorien passt.

                %s

                Inhalt:
                Titel: %s
                %s

                Antworte mit GENAU einer Zeile im Format:
                Kategoriepfad|Konfidenz

                Kategoriepfad exakt wie oben gelistet (inkl. " > " bei Unterkategorien).
                Konfidenz ist eine Zahl zwischen 0 und 1. Keine weitere Erklärung.
                """.formatted(PromptSanitizer.GUARD_NOTE,
                title == null ? "" : title.replaceAll("[\\r\\n]", " "),
                PromptSanitizer.wrapUntrusted(content, MAX_CONTENT_CHARS)));
        return prompt.toString();
    }

    private Optional<Result> parse(String answer, List<Category> categories, Map<Long, String> paths) {
        String line = answer.strip().lines().findFirst().orElse("");
        String namePart = line;
        float confidence = 0.5f;
        int sep = line.lastIndexOf('|');
        if (sep > 0) {
            namePart = line.substring(0, sep).strip();
            try {
                confidence = Float.parseFloat(line.substring(sep + 1).strip().replace(',', '.'));
            } catch (NumberFormatException ignored) {
            }
        }
        // Beschreibungs-Anhang abschneiden ("Technik > KI: PostgreSQL" → "Technik > KI")
        int colon = namePart.indexOf(':');
        final String name = (colon > 0 ? namePart.substring(0, colon) : namePart).strip();

        // Match-Kaskade — kleine Modelle erfinden gern Pfad-Ebenen:
        // 1. exakter Pfad  2. exakter Name  3. letztes Pfadsegment  4. erstes Segment
        // 5. Kategoriename kommt in der Antwort vor (längster zuerst)
        String[] segments = name.split(">");
        String lastSegment = segments[segments.length - 1].strip();
        String firstSegment = segments[0].strip();
        Optional<Category> match = categories.stream()
                .filter(c -> paths.get(c.getId()).equalsIgnoreCase(name))
                .findFirst()
                .or(() -> byName(categories, name))
                .or(() -> byName(categories, lastSegment))
                .or(() -> byName(categories, firstSegment))
                .or(() -> categories.stream()
                        .sorted((a, b) -> b.getName().length() - a.getName().length())
                        .filter(c -> c.getName().length() > 2
                                && name.toLowerCase().contains(c.getName().toLowerCase()))
                        .findFirst());
        if (match.isEmpty()) {
            log.info("LLM-Antwort '{}' passt zu keiner Kategorie", line);
            return Optional.empty();
        }
        return Optional.of(new Result(match.get(), Math.clamp(confidence, 0f, 1f)));
    }

    private Optional<Category> byName(List<Category> categories, String name) {
        return categories.stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    public record Result(Category category, float confidence) {
    }
}
