package com.summarizer.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Gleicht die pgvector-Spaltendimension mit dem konfigurierten Embedding-Modell ab
 * (Sprache wird bei der Installation gewählt: nomic-embed-text=768, bge-m3=1024).
 * Anpassung nur möglich, solange noch keine Embeddings gespeichert sind —
 * danach wäre ein Re-Embedding aller Inhalte nötig.
 */
@Component
public class EmbeddingDimensionGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingDimensionGuard.class);

    private final JdbcTemplate jdbc;
    private final int configuredDim;

    public EmbeddingDimensionGuard(JdbcTemplate jdbc,
                                   @Value("${summarizer.embedding-dim:768}") int configuredDim) {
        this.jdbc = jdbc;
        this.configuredDim = configuredDim;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer currentDim = jdbc.queryForObject("""
                SELECT atttypmod FROM pg_attribute
                WHERE attrelid = 'item_embeddings'::regclass AND attname = 'embedding'
                """, Integer.class);
        if (currentDim == null || currentDim == configuredDim) {
            return;
        }
        Long count = jdbc.queryForObject("SELECT count(*) FROM item_embeddings", Long.class);
        if (count != null && count > 0) {
            log.error("Embedding-Dimension der DB ({}) passt nicht zum Modell ({}), aber es existieren "
                    + "{} Embeddings. Re-Embedding nötig: item_embeddings leeren und Inhalte neu verarbeiten.",
                    currentDim, configuredDim, count);
            return;
        }
        log.info("Passe Embedding-Dimension an: {} → {}", currentDim, configuredDim);
        jdbc.execute("DROP INDEX IF EXISTS idx_item_embeddings_hnsw");
        jdbc.execute("ALTER TABLE item_embeddings ALTER COLUMN embedding TYPE vector(" + configuredDim + ")");
        jdbc.execute("CREATE INDEX idx_item_embeddings_hnsw ON item_embeddings "
                + "USING hnsw (embedding vector_cosine_ops)");
    }
}
