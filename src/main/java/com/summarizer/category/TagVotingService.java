package com.summarizer.category;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tag-Voting: Kategorisierung ohne LLM.
 * Zwei Signale: (1) Nachbarschaft — in welcher Kategorie liegen die Inhalte,
 * die dieselben Tags tragen; (2) Keyword-Match — Tag kommt wörtlich in der
 * Kategorie-Beschreibung vor (deckt den Kaltstart ab).
 * Nur ein KLARER Sieger zählt (Mindestscore + 2:1 gegen Platz zwei).
 */
@Service
public class TagVotingService {

    private static final Logger log = LoggerFactory.getLogger(TagVotingService.class);
    private static final int MIN_SCORE = 3;
    private static final double MARGIN = 2.0;
    private static final int KEYWORD_WEIGHT = 3;
    public static final float VOTE_CONFIDENCE = 0.75f;

    private final JdbcTemplate jdbc;

    public TagVotingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Vote(Long categoryId, float confidence) {
    }

    /** Klarer Tag-Sieger für das Item oder empty (dann entscheidet das LLM). */
    public Optional<Vote> vote(Long userId, Long itemId, List<Long> excludedCategoryIds) {
        List<String> tagNames = jdbc.queryForList("""
                SELECT t.name FROM item_tags it JOIN tags t ON t.id = it.tag_id
                WHERE it.item_id = ?
                """, String.class, itemId);
        if (tagNames.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, Double> scores = new HashMap<>();

        // Signal 1: Nachbarschaft — Kategorien der Items mit denselben Tags
        jdbc.query("""
                SELECT i.category_id AS cat, count(DISTINCT it2.item_id) AS votes
                FROM item_tags it1
                JOIN item_tags it2 ON it2.tag_id = it1.tag_id AND it2.item_id <> it1.item_id
                JOIN items i ON i.id = it2.item_id
                WHERE it1.item_id = ? AND i.user_id = ? AND i.category_id IS NOT NULL
                GROUP BY i.category_id
                """, rs -> {
            scores.merge(rs.getLong("cat"), (double) rs.getLong("votes"), Double::sum);
        }, itemId, userId);

        // Signal 2: Tag steht wörtlich in der Kategorie-Beschreibung oder im Namen
        jdbc.query("SELECT id, name, coalesce(description, '') AS description FROM categories WHERE user_id = ?",
                rs -> {
                    long categoryId = rs.getLong("id");
                    String haystack = (rs.getString("name") + " " + rs.getString("description"))
                            .toLowerCase();
                    for (String tag : tagNames) {
                        if (tag.length() > 2 && haystack.contains(tag.toLowerCase())) {
                            scores.merge(categoryId, (double) KEYWORD_WEIGHT, Double::sum);
                        }
                    }
                }, userId);

        excludedCategoryIds.forEach(scores::remove);
        if (scores.isEmpty()) {
            return Optional.empty();
        }
        List<Map.Entry<Long, Double>> ranked = scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .toList();
        double top = ranked.getFirst().getValue();
        double second = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
        if (top < MIN_SCORE || (second > 0 && top < second * MARGIN)) {
            return Optional.empty();   // kein klarer Sieger -> LLM entscheidet
        }
        log.debug("Tag-Voting: Item {} -> Kategorie {} (Score {} vs. {})",
                itemId, ranked.getFirst().getKey(), top, second);
        return Optional.of(new Vote(ranked.getFirst().getKey(), VOTE_CONFIDENCE));
    }
}
