package com.summarizer.item.api;

import com.summarizer.base.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Export aller eigenen Inhalte als JSON oder komplettes ZIP-Backup
 * (Session-Auth, Download im Studio).
 */
@RestController
public class ExportController {

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public ExportController(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping(value = "/export/items.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> export() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.id, i.type, i.title, i.source_url, i.summary, i.raw_text,
                       i.favorite, i.created_at, c.name AS category,
                       (SELECT string_agg(t.name, ',') FROM item_tags it
                        JOIN tags t ON t.id = it.tag_id WHERE it.item_id = i.id) AS tags
                FROM items i LEFT JOIN categories c ON c.id = i.category_id
                WHERE i.user_id = ?
                ORDER BY i.created_at
                """, currentUser.id());
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"summarizer-export-" + LocalDate.now() + ".json\"")
                .body(rows);
    }

    /**
     * Komplettes Backup als ZIP: alle Tabellen-Inhalte des Users als JSON
     * plus die abgelegten Original-Dateien und Webseiten-Snapshots.
     */
    @GetMapping("/export/archive.zip")
    public void exportZip(HttpServletResponse response) throws IOException {
        Long userId = currentUser.id();
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"summarizer-backup-" + LocalDate.now() + ".zip\"");

        var mapper = new tools.jackson.databind.ObjectMapper();
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream())) {
            putJson(zip, mapper, "items.json", jdbc.queryForList("""
                    SELECT i.id, i.type, i.title, i.source_url, i.summary, i.raw_text,
                           i.favorite, i.status, i.error_message,
                           i.created_at::text AS created_at, i.captured_at::text AS captured_at,
                           i.file_path, i.snapshot_path, c.name AS category,
                           (SELECT string_agg(t.name, ',') FROM item_tags it
                            JOIN tags t ON t.id = it.tag_id WHERE it.item_id = i.id) AS tags
                    FROM items i LEFT JOIN categories c ON c.id = i.category_id
                    WHERE i.user_id = ? ORDER BY i.id
                    """, userId));
            putJson(zip, mapper, "categories.json", jdbc.queryForList("""
                    SELECT c.id, c.name, c.description, c.color, c.sort_order, c.system_type,
                           p.name AS parent
                    FROM categories c LEFT JOIN categories p ON p.id = c.parent_id
                    WHERE c.user_id = ? ORDER BY c.sort_order, c.name
                    """, userId));
            putJson(zip, mapper, "entities.json", jdbc.queryForList("""
                    SELECT id, name, type, description FROM entities
                    WHERE user_id = ? ORDER BY name
                    """, userId));
            putJson(zip, mapper, "relations.json", jdbc.queryForList("""
                    SELECT s.name AS source, r.relation, t.name AS target, r.item_id
                    FROM entity_relations r
                    JOIN entities s ON s.id = r.source_id
                    JOIN entities t ON t.id = r.target_id
                    WHERE r.user_id = ? ORDER BY r.id
                    """, userId));
            putJson(zip, mapper, "chat.json", jdbc.queryForList("""
                    SELECT role, text, created_at::text AS created_at FROM chat_messages
                    WHERE user_id = ? ORDER BY id
                    """, userId));

            List<Map<String, Object>> fileRows = jdbc.queryForList("""
                    SELECT id, file_path, snapshot_path FROM items
                    WHERE user_id = ? AND (file_path IS NOT NULL OR snapshot_path IS NOT NULL)
                    """, userId);
            for (Map<String, Object> row : fileRows) {
                putFile(zip, row.get("id"), (String) row.get("file_path"), "files/");
                putFile(zip, row.get("id"), (String) row.get("snapshot_path"), "snapshots/");
            }
        }
    }

    private void putJson(ZipOutputStream zip, tools.jackson.databind.ObjectMapper mapper,
                         String name, Object data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
        zip.closeEntry();
    }

    private void putFile(ZipOutputStream zip, Object itemId, String pathValue, String folder)
            throws IOException {
        if (pathValue == null) {
            return;
        }
        Path path = Path.of(pathValue);
        if (!Files.exists(path)) {
            return;
        }
        zip.putNextEntry(new ZipEntry(folder + itemId + "_" + path.getFileName()));
        Files.copy(path, zip);
        zip.closeEntry();
    }
}
