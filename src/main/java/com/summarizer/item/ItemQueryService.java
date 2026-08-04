package com.summarizer.item;

import com.summarizer.ai.EmbeddingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Kombinierte Item-Suche für das Studio: Volltext ODER Vektorsuche,
 * jeweils kombinierbar mit Kategorie-, Typ- und Datumsfiltern.
 */
@Service
public class ItemQueryService {

    private final JdbcTemplate jdbc;
    private final EmbeddingService embeddings;

    public ItemQueryService(JdbcTemplate jdbc, EmbeddingService embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    public record Filter(String text, boolean semantic, List<Long> categoryIds, boolean unsortedOnly,
                         boolean favoritesOnly, Item.Type type, LocalDate from, LocalDate to,
                         List<String> sortKeys, List<String> tags, boolean deadLinksOnly) {

        public static Filter empty() {
            return new Filter(null, false, null, false, false, null, null, null, null, null, false);
        }

        boolean hasTags() {
            return tags != null && !tags.isEmpty();
        }

        boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    public record Card(long id, String title, String type, String categoryName, String categoryColor,
                       Float confidence, Instant createdAt, String snippet, String sourceUrl,
                       Double distance, boolean favorite, String summary, String thumbnailUrl,
                       String status, String tags, Boolean linkOk) {
    }

    public List<Card> find(Long userId, Filter filter, int offset, int limit) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        boolean semantic = filter.semantic() && filter.hasText();
        String vector = null;
        if (semantic) {
            Optional<String> embedded = embeddings.embedQueryVector(filter.text());
            if (embedded.isEmpty()) {
                semantic = false;   // Ollama nicht erreichbar → Fallback Volltext
            } else {
                vector = embedded.get();
            }
        }

        sql.append("""
                SELECT i.id, i.title, i.type, i.created_at, i.category_confidence, i.source_url,
                       i.favorite, i.summary, i.thumbnail_url, i.status, i.link_ok,
                       left(coalesce(i.raw_text, ''), 240) AS snippet,
                       c.name AS category_name, c.color AS category_color,
                       (SELECT string_agg(t.name, ',') FROM item_tags it
                        JOIN tags t ON t.id = it.tag_id WHERE it.item_id = i.id) AS tags
                """);
        if (semantic) {
            sql.append(", d.distance\n");
        } else {
            sql.append(", NULL::float AS distance\n");
        }
        sql.append("FROM items i LEFT JOIN categories c ON c.id = i.category_id\n");
        if (semantic) {
            sql.append("""
                    JOIN LATERAL (
                        SELECT min(e.embedding <=> ?::vector) AS distance
                        FROM item_embeddings e WHERE e.item_id = i.id
                    ) d ON d.distance IS NOT NULL
                    """);
            params.add(vector);
        }
        sql.append("WHERE i.user_id = ?\n");
        params.add(userId);

        if (filter.unsortedOnly()) {
            sql.append("AND (i.category_id IS NULL OR i.category_confidence < 0.5)\n");
        } else if (filter.favoritesOnly()) {
            // Stern-Markierung ODER manuell in den Favoriten-Teilbaum einsortiert
            if (filter.categoryIds() != null && !filter.categoryIds().isEmpty()) {
                sql.append("AND (i.favorite = TRUE OR i.category_id IN (")
                        .append(String.join(",", java.util.Collections.nCopies(filter.categoryIds().size(), "?")))
                        .append("))\n");
                params.addAll(filter.categoryIds());
            } else {
                sql.append("AND i.favorite = TRUE\n");
            }
        } else if (filter.categoryIds() != null && !filter.categoryIds().isEmpty()) {
            sql.append("AND i.category_id IN (")
                    .append(String.join(",", java.util.Collections.nCopies(filter.categoryIds().size(), "?")))
                    .append(")\n");
            params.addAll(filter.categoryIds());
        }
        if (filter.type() != null) {
            sql.append("AND i.type = ?\n");
            params.add(filter.type().name());
        }
        if (filter.deadLinksOnly()) {
            sql.append("AND i.link_ok = FALSE\n");
        }
        if (filter.from() != null) {
            sql.append("AND i.created_at >= ?\n");
            params.add(Timestamp.from(filter.from().atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        if (filter.to() != null) {
            sql.append("AND i.created_at < ?\n");
            params.add(Timestamp.from(filter.to().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        if (!semantic && filter.hasText()) {
            sql.append("AND (i.title ILIKE ? OR i.raw_text ILIKE ?)\n");
            String like = "%" + filter.text().trim() + "%";
            params.add(like);
            params.add(like);
        }

        sql.append(semantic ? "ORDER BY d.distance\n" : orderByClause(filter.sortKeys()));
        sql.append("LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbc.query(sql.toString(), (rs, rowNum) -> new Card(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("type"),
                        rs.getString("category_name"),
                        rs.getString("category_color"),
                        rs.getObject("category_confidence", Float.class),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("snippet"),
                        rs.getString("source_url"),
                        rs.getObject("distance", Double.class),
                        rs.getBoolean("favorite"),
                        rs.getString("summary"),
                        rs.getString("thumbnail_url"),
                        rs.getString("status"),
                        rs.getString("tags"),
                        rs.getObject("link_ok", Boolean.class)),
                params.toArray());
    }

    /** ORDER BY aus der User-Sortierreihenfolge (Badges), z. B. [CATEGORY, DATE, TYPE]. */
    private String orderByClause(List<String> sortKeys) {
        List<String> keys = sortKeys == null || sortKeys.isEmpty()
                ? List.of("DATE") : sortKeys;
        List<String> parts = new ArrayList<>();
        for (String key : keys) {
            switch (key) {
                case "CATEGORY" -> parts.add("category_name ASC NULLS LAST");
                case "TYPE" -> parts.add("i.type ASC");
                default -> parts.add("i.created_at DESC");
            }
        }
        return "ORDER BY " + String.join(", ", parts) + "\n";
    }

    public int countUnsorted(Long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM items
                WHERE user_id = ? AND (category_id IS NULL OR category_confidence < 0.5)
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }
}
