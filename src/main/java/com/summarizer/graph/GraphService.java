package com.summarizer.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Wissensgraph: Entitäten und Beziehungen in Postgres.
 */
@Service
public class GraphService {

    private final JdbcTemplate jdbc;

    public GraphService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Entity(long id, String name, String type, String description, long degree,
                         Long categoryId, String categoryName, String categoryColor) {
    }

    public record Relation(long sourceId, String sourceName, String relation,
                           long targetId, String targetName, int weight) {
    }

    @Transactional
    public long upsertEntity(Long userId, String name, String type, String description) {
        // case-insensitive Dedup: "PostgreSQL" und "postgresql" sind dieselbe Entität
        return jdbc.queryForObject("""
                INSERT INTO entities (user_id, name, type, description)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, lower(name)) DO UPDATE
                    SET description = COALESCE(NULLIF(entities.description, ''), EXCLUDED.description),
                        type = entities.type
                RETURNING id
                """, Long.class, userId, name.strip(), type, description);
    }

    /** Items, in denen die Entität vorkommt (für Knoten-Klick in der Visualisierung). */
    public List<LinkedItem> itemsForEntity(Long userId, long entityId) {
        return jdbc.query("""
                SELECT i.id, i.title, i.type
                FROM item_entities ie
                JOIN items i ON i.id = ie.item_id
                WHERE ie.entity_id = ? AND i.user_id = ?
                ORDER BY i.created_at DESC
                """, (rs, n) -> new LinkedItem(rs.getLong("id"), rs.getString("title"),
                        rs.getString("type")),
                entityId, userId);
    }

    public record LinkedItem(long itemId, String title, String type) {
    }

    @Transactional
    public void linkItem(long itemId, long entityId) {
        jdbc.update("""
                INSERT INTO item_entities (item_id, entity_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, itemId, entityId);
    }

    @Transactional
    public void addRelation(Long userId, long sourceId, long targetId, String relation, Long itemId) {
        Integer exists = jdbc.queryForObject("""
                SELECT count(*) FROM entity_relations
                WHERE user_id = ? AND source_id = ? AND target_id = ?
                """, Integer.class, userId, sourceId, targetId);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO entity_relations (user_id, source_id, target_id, relation, item_id)
                VALUES (?, ?, ?, ?, ?)
                """, userId, sourceId, targetId, relation, itemId);
    }

    /** Entitäten inkl. dominanter Kategorie (häufigste Kategorie der verknüpften Items). */
    public List<Entity> entities(Long userId) {
        return jdbc.query("""
                SELECT e.id, e.name, e.type, e.description,
                       (SELECT count(*) FROM entity_relations r
                        WHERE r.source_id = e.id OR r.target_id = e.id) AS degree,
                       cat.id AS category_id, cat.name AS category_name, cat.color AS category_color
                FROM entities e
                LEFT JOIN LATERAL (
                    SELECT c.id, c.name, c.color
                    FROM item_entities ie
                    JOIN items i ON i.id = ie.item_id
                    JOIN categories c ON c.id = i.category_id
                    WHERE ie.entity_id = e.id
                    GROUP BY c.id, c.name, c.color
                    ORDER BY count(*) DESC, c.id
                    LIMIT 1
                ) cat ON true
                WHERE e.user_id = ?
                ORDER BY degree DESC, e.name
                """, (rs, i) -> new Entity(rs.getLong("id"), rs.getString("name"),
                        rs.getString("type"), rs.getString("description"), rs.getLong("degree"),
                        rs.getObject("category_id", Long.class),
                        rs.getString("category_name"), rs.getString("category_color")),
                userId);
    }

    /** IDs aller Entitäten, die in den gegebenen Items vorkommen (semantischer Graph-Filter). */
    public java.util.Set<Long> entityIdsForItems(Long userId, List<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return java.util.Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
        Object[] params = new Object[itemIds.size() + 1];
        params[0] = userId;
        for (int i = 0; i < itemIds.size(); i++) {
            params[i + 1] = itemIds.get(i);
        }
        return new java.util.HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT ie.entity_id
                FROM item_entities ie
                JOIN entities e ON e.id = ie.entity_id
                WHERE e.user_id = ? AND ie.item_id IN (%s)
                """.formatted(placeholders), Long.class, params));
    }

    /** Beziehungen mit Gewicht = Anzahl gemeinsamer Inhalte (Kookkurrenz). */
    public List<Relation> relations(Long userId) {
        return jdbc.query("""
                SELECT r.source_id, s.name AS source_name, r.relation, r.target_id,
                       t.name AS target_name, COALESCE(co.w, 1) AS weight
                FROM entity_relations r
                JOIN entities s ON s.id = r.source_id
                JOIN entities t ON t.id = r.target_id
                LEFT JOIN (
                    SELECT LEAST(ie1.entity_id, ie2.entity_id) AS a,
                           GREATEST(ie1.entity_id, ie2.entity_id) AS b,
                           count(DISTINCT ie1.item_id) AS w
                    FROM item_entities ie1
                    JOIN item_entities ie2 ON ie1.item_id = ie2.item_id
                                          AND ie1.entity_id < ie2.entity_id
                    GROUP BY 1, 2
                ) co ON co.a = LEAST(r.source_id, r.target_id)
                    AND co.b = GREATEST(r.source_id, r.target_id)
                WHERE r.user_id = ?
                ORDER BY s.name
                """, (rs, i) -> new Relation(rs.getLong("source_id"), rs.getString("source_name"),
                        rs.getString("relation"), rs.getLong("target_id"), rs.getString("target_name"),
                        rs.getInt("weight")),
                userId);
    }

    /** Verlierer-Entität in Gewinner aufgehen lassen (Dedup nach dem Rebuild). */
    @Transactional
    public void mergeEntities(Long userId, long winnerId, long loserId) {
        jdbc.update("""
                UPDATE item_entities SET entity_id = ?
                WHERE entity_id = ? AND NOT EXISTS (
                    SELECT 1 FROM item_entities ie2
                    WHERE ie2.item_id = item_entities.item_id AND ie2.entity_id = ?)
                """, winnerId, loserId, winnerId);
        jdbc.update("DELETE FROM item_entities WHERE entity_id = ?", loserId);
        jdbc.update("UPDATE entity_relations SET source_id = ? WHERE source_id = ? AND user_id = ?",
                winnerId, loserId, userId);
        jdbc.update("UPDATE entity_relations SET target_id = ? WHERE target_id = ? AND user_id = ?",
                winnerId, loserId, userId);
        jdbc.update("DELETE FROM entity_relations WHERE user_id = ? AND source_id = target_id", userId);
        // doppelte Kanten nach dem Umhaengen entfernen
        jdbc.update("""
                DELETE FROM entity_relations a USING entity_relations b
                WHERE a.user_id = ? AND b.user_id = a.user_id AND a.id > b.id
                  AND a.source_id = b.source_id AND a.target_id = b.target_id
                """, userId);
        jdbc.update("DELETE FROM entities WHERE id = ? AND user_id = ?", loserId, userId);
    }

    /** Entitäten, deren Name in der Frage vorkommt (Graph-Einstiegspunkte fürs Retrieval). */
    public List<Entity> findEntitiesInText(Long userId, String text) {
        return jdbc.query("""
                SELECT e.id, e.name, e.type, e.description, 0 AS degree
                FROM entities e
                WHERE e.user_id = ? AND length(e.name) > 2 AND ? ILIKE '%' || e.name || '%'
                LIMIT 8
                """, (rs, i) -> new Entity(rs.getLong("id"), rs.getString("name"),
                        rs.getString("type"), rs.getString("description"), 0, null, null, null),
                userId, text);
    }

    /** Alle Beziehungen, an denen die Entitäten beteiligt sind (1-Hop-Nachbarschaft). */
    public List<Relation> neighborhood(Long userId, List<Long> entityIds) {
        if (entityIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(entityIds.size(), "?"));
        Object[] params = new Object[entityIds.size() * 2 + 1];
        params[0] = userId;
        for (int i = 0; i < entityIds.size(); i++) {
            params[1 + i] = entityIds.get(i);
            params[1 + entityIds.size() + i] = entityIds.get(i);
        }
        return jdbc.query("""
                SELECT r.source_id, s.name AS source_name, r.relation, r.target_id, t.name AS target_name
                FROM entity_relations r
                JOIN entities s ON s.id = r.source_id
                JOIN entities t ON t.id = r.target_id
                WHERE r.user_id = ? AND (r.source_id IN (%s) OR r.target_id IN (%s))
                LIMIT 30
                """.formatted(placeholders, placeholders),
                (rs, i) -> new Relation(rs.getLong("source_id"), rs.getString("source_name"),
                        rs.getString("relation"), rs.getLong("target_id"), rs.getString("target_name"), 1),
                params);
    }

    /** Kompletten Graph eines Users löschen (Relationen + Verknüpfungen via CASCADE). */
    @Transactional
    public void deleteAllForUser(Long userId) {
        jdbc.update("DELETE FROM entities WHERE user_id = ?", userId);
    }

    public boolean itemHasEntities(long itemId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM item_entities WHERE item_id = ?", Integer.class, itemId);
        return count != null && count > 0;
    }
}
