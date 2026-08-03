package com.summarizer.item.api;

import com.summarizer.base.CurrentUser;
import com.summarizer.item.Item;
import com.summarizer.item.ItemRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Liefert abgelegte Dateien für die Studio-Vorschau aus — strikt nur
 * für Items des eingeloggten Users (Session-Auth über die Vaadin-Filterkette).
 */
@RestController
public class FileController {

    private final ItemRepository items;
    private final CurrentUser currentUser;

    public FileController(ItemRepository items, CurrentUser currentUser) {
        this.items = items;
        this.currentUser = currentUser;
    }

    @GetMapping("/files/{itemId}")
    public ResponseEntity<FileSystemResource> file(@PathVariable Long itemId) throws Exception {
        Item item = ownItem(itemId);
        if (item == null || item.getFilePath() == null) {
            return ResponseEntity.notFound().build();
        }
        return serve(Path.of(item.getFilePath()), false);
    }

    /** Offline-Kopie der Webseite — als Download (nicht inline, wegen fremdem Skript-Code). */
    @GetMapping("/files/{itemId}/snapshot")
    public ResponseEntity<FileSystemResource> snapshot(@PathVariable Long itemId) throws Exception {
        Item item = ownItem(itemId);
        if (item == null || item.getSnapshotPath() == null) {
            return ResponseEntity.notFound().build();
        }
        return serve(Path.of(item.getSnapshotPath()), true);
    }

    private Item ownItem(Long itemId) {
        try {
            return items.findByIdAndUserId(itemId, currentUser.id()).orElse(null);
        } catch (Exception e) {
            return null;   // kein eingeloggter User
        }
    }

    private ResponseEntity<FileSystemResource> serve(Path path, boolean attachment) throws Exception {
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        String contentType = Files.probeContentType(path);
        var builder = ResponseEntity.ok()
                .contentType(contentType == null
                        ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType));
        if (attachment) {
            builder = builder.header("Content-Disposition",
                    "attachment; filename=\"" + path.getFileName() + "\"");
        }
        return builder.body(new FileSystemResource(path));
    }
}
