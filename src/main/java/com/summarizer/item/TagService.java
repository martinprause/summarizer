package com.summarizer.item;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Freie Tags zusätzlich zu Kategorien; entstehen implizit beim Zuweisen.
 */
@Service
public class TagService {

    private final JdbcTemplate jdbc;

    public TagService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Die 40 meistgenutzten Tags — als Vokabular-Vorschlag in LLM-Prompts. */
    public List<String> allTagNames(Long userId) {
        return jdbc.queryForList("""
                SELECT t.name FROM tags t
                LEFT JOIN item_tags it ON it.tag_id = t.id
                WHERE t.user_id = ?
                GROUP BY t.name
                ORDER BY count(it.item_id) DESC, t.name
                LIMIT 40
                """, String.class, userId);
    }

    /** Alle Tags des Users — fuer die Tag-Suche im Dashboard. */
    public List<String> allTagNamesUnlimited(Long userId) {
        return jdbc.queryForList(
                "SELECT name FROM tags WHERE user_id = ? ORDER BY name", String.class, userId);
    }

    public List<String> tagsForItem(long itemId) {
        return jdbc.queryForList("""
                SELECT t.name FROM item_tags it JOIN tags t ON t.id = it.tag_id
                WHERE it.item_id = ? ORDER BY t.name
                """, String.class, itemId);
    }

    @Transactional
    public void setTags(Long userId, long itemId, List<String> names) {
        jdbc.update("DELETE FROM item_tags WHERE item_id = ?", itemId);
        for (String raw : names) {
            String name = raw.strip();
            if (name.isEmpty() || name.length() > 60) {
                continue;
            }
            Long tagId = jdbc.queryForObject("""
                    INSERT INTO tags (user_id, name) VALUES (?, ?)
                    ON CONFLICT (user_id, name) DO UPDATE SET name = EXCLUDED.name
                    RETURNING id
                    """, Long.class, userId, name);
            jdbc.update("INSERT INTO item_tags (item_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    itemId, tagId);
        }
        // verwaiste Tags aufraeumen
        jdbc.update("""
                DELETE FROM tags WHERE user_id = ?
                AND NOT EXISTS (SELECT 1 FROM item_tags it WHERE it.tag_id = tags.id)
                """, userId);
    }
}
