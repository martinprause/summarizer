package com.summarizer.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Chunking, Vektorisierung und pgvector-Speicherung/-Suche.
 * item_embeddings wird per JDBC verwaltet (kein JPA-Mapping für den vector-Typ nötig).
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int CHUNK_SIZE = 1200;
    private static final int CHUNK_OVERLAP = 150;
    private static final int MAX_CHUNKS = 64;

    private final OllamaClient ollama;
    private final JdbcTemplate jdbc;
    private final com.summarizer.base.JobProgressService progress;

    public EmbeddingService(OllamaClient ollama, JdbcTemplate jdbc,
                            com.summarizer.base.JobProgressService progress) {
        this.ollama = ollama;
        this.jdbc = jdbc;
        this.progress = progress;
    }

    @Transactional
    public int embedAndStore(Long itemId, String text) {
        List<String> chunks = chunk(text);
        if (chunks.isEmpty()) {
            return 0;
        }
        List<List<Double>> vectors = ollama.embed(chunks);
        if (vectors.size() != chunks.size()) {
            log.warn("Embedding für Item {} unvollständig ({} von {} Chunks)", itemId, vectors.size(), chunks.size());
            return 0;
        }
        ensureDimension(vectors.getFirst().size());
        jdbc.update("DELETE FROM item_embeddings WHERE item_id = ?", itemId);
        for (int i = 0; i < chunks.size(); i++) {
            jdbc.update("""
                    INSERT INTO item_embeddings (item_id, chunk_index, chunk_text, embedding)
                    VALUES (?, ?, ?, ?::vector)
                    """, itemId, i, chunks.get(i), toVectorLiteral(vectors.get(i)));
        }
        return chunks.size();
    }

    /**
     * Alle Inhalte neu vektorisieren — nötig nach Wechsel des Embedding-Modells.
     * Läuft im Hintergrund, Fortschritt im Log.
     */
    @org.springframework.scheduling.annotation.Async
    public void reembedAllAsync() {
        List<Long> itemIds = jdbc.queryForList(
                "SELECT id FROM items WHERE status = 'DONE' ORDER BY id", Long.class);
        String key = com.summarizer.base.JobProgressService.reembedKey();
        progress.start(key, "Neu vektorisieren", itemIds.size());
        log.info("Re-Embedding gestartet: {} Items", itemIds.size());
        int done = 0;
        int processed = 0;
        for (Long itemId : itemIds) {
            try {
                String text = jdbc.queryForObject("""
                        SELECT coalesce(title,'') || E'\\n' || coalesce(summary,'')
                               || E'\\n' || coalesce(raw_text,'')
                        FROM items WHERE id = ?
                        """, String.class, itemId);
                embedAndStore(itemId, text);
                done++;
            } catch (Exception e) {
                log.warn("Re-Embedding für Item {} fehlgeschlagen: {}", itemId, e.getMessage());
            }
            progress.update(key, ++processed);
        }
        progress.finish(key, done + " von " + itemIds.size() + " Items neu vektorisiert");
        log.info("Re-Embedding fertig: {}/{} Items", done, itemIds.size());
    }

    /**
     * Spalten-Dimension an das aktive Embedding-Modell anpassen (z. B. nomic 768,
     * qwen3-embedding 2560). Bei Wechsel: alte Vektoren sind wertlos — Tabelle leeren,
     * Spaltentyp ändern; der Embedding-Backfill füllt sie danach wieder auf.
     * HNSW-Index nur bis 2000 Dimensionen (pgvector-Limit), darüber exakte Suche.
     */
    private synchronized void ensureDimension(int dims) {
        int current = columnDimensions();
        if (current == dims) {
            return;
        }
        log.warn("Embedding-Dimension wechselt von {} auf {} — Vektor-Tabelle wird neu aufgebaut",
                current, dims);
        jdbc.execute("DROP INDEX IF EXISTS idx_item_embeddings_hnsw");
        jdbc.update("DELETE FROM item_embeddings");
        jdbc.execute("ALTER TABLE item_embeddings ALTER COLUMN embedding TYPE vector(" + dims + ")");
        if (dims <= 2000) {
            jdbc.execute("CREATE INDEX idx_item_embeddings_hnsw ON item_embeddings "
                    + "USING hnsw (embedding vector_cosine_ops)");
        } else {
            log.info("Dimension {} > 2000: HNSW-Index entfällt, pgvector sucht exakt", dims);
        }
    }

    /** Aktuelle Dimension der embedding-Spalte (atttypmod = Dimension bei pgvector). */
    private int columnDimensions() {
        Integer mod = jdbc.queryForObject("""
                SELECT atttypmod FROM pg_attribute
                WHERE attrelid = 'item_embeddings'::regclass AND attname = 'embedding'
                """, Integer.class);
        return mod == null ? -1 : mod;
    }

    /** Embedding einer Suchanfrage als pgvector-Literal, leer wenn Ollama nicht erreichbar. */
    public java.util.Optional<String> embedQueryVector(String query) {
        List<List<Double>> vectors = ollama.embed(List.of(query));
        return vectors.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(toVectorLiteral(vectors.getFirst()));
    }

    /** Semantische Suche über alle Items eines Users. */
    public List<SearchHit> search(Long userId, String query, int limit) {
        List<List<Double>> vectors = ollama.embed(List.of(query));
        if (vectors.isEmpty()) {
            return List.of();
        }
        // Modellwechsel, Backfill noch nicht durch: Anfrage-Vektor passt nicht zur Spalte
        if (vectors.getFirst().size() != columnDimensions()) {
            log.warn("Suche übersprungen: Embedding-Dimension {} passt nicht zur Tabelle ({}) — "
                    + "Neu-Vektorisierung läuft noch", vectors.getFirst().size(), columnDimensions());
            return List.of();
        }
        String vector = toVectorLiteral(vectors.getFirst());
        return jdbc.query("""
                SELECT DISTINCT ON (i.id) i.id, i.title, i.type, i.source_url, i.summary,
                       e.chunk_text, (e.embedding <=> ?::vector) AS distance
                FROM item_embeddings e
                JOIN items i ON i.id = e.item_id
                WHERE i.user_id = ?
                ORDER BY i.id, distance
                """, (rs, rowNum) -> new SearchHit(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("type"),
                        rs.getString("source_url"),
                        rs.getString("chunk_text"),
                        rs.getDouble("distance"),
                        rs.getString("summary")),
                vector, userId)
                .stream()
                .sorted((a, b) -> Double.compare(a.distance(), b.distance()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    static List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.strip();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length() && chunks.size() < MAX_CHUNKS) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            chunks.add(normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }

    private static String toVectorLiteral(List<Double> vector) {
        return vector.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    public record SearchHit(Long itemId, String title, String type, String sourceUrl,
                            String chunkText, double distance, String summary) {
    }
}
