package com.summarizer.item.api;

import com.summarizer.ai.EmbeddingService;
import com.summarizer.category.CategoryRepository;
import com.summarizer.item.Item;
import com.summarizer.item.ItemRepository;
import com.summarizer.item.pipeline.IngestPipeline;
import com.summarizer.token.TokenAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ItemApiController {

    private final ItemRepository items;
    private final CategoryRepository categories;
    private final IngestPipeline pipeline;
    private final EmbeddingService embeddings;
    private final Path filesDir;

    public ItemApiController(ItemRepository items, CategoryRepository categories,
                             IngestPipeline pipeline, EmbeddingService embeddings,
                             @Value("${summarizer.files.dir}") String filesDir) {
        this.items = items;
        this.categories = categories;
        this.pipeline = pipeline;
        this.embeddings = embeddings;
        this.filesDir = Path.of(filesDir);
    }

    public record CreateItemRequest(String type, String title, String url, String text) {
    }

    @PostMapping(value = "/items", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createItem(@RequestBody CreateItemRequest request, HttpServletRequest http) {
        Long userId = userId(http);
        String url = request.url();
        String text = request.text();
        // Clients wie iOS-Kurzbefehle schicken alles als text —
        // ist der Text nur eine URL, als Webseite behandeln.
        if ((url == null || url.isBlank()) && text != null
                && text.strip().matches("https?://\\S+")) {
            url = text.strip();
            text = null;
        }
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasText = text != null && !text.isBlank();
        if (!hasUrl && !hasText) {
            return ResponseEntity.badRequest().body(Map.of("error", "url oder text erforderlich"));
        }

        // Dedup: gleiche URL beim gleichen User -> bestehendes Item zurueckgeben
        if (hasUrl) {
            var existing = items.findFirstByUserIdAndSourceUrl(userId, url.trim());
            if (existing.isPresent()) {
                var response = toResponse(existing.get());
                response.put("duplicate", true);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
            }
        }

        Item.Type type = resolveType(request.type(), hasUrl);
        Item item = new Item(userId, type);
        item.setTitle(request.title());
        item.setSourceUrl(hasUrl ? url.trim() : null);
        item.setRawText(hasText ? text : null);
        items.save(item);
        pipeline.process(item.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(item));
    }

    @PostMapping(value = "/items", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadItem(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "title", required = false) String title,
                                        HttpServletRequest http) throws Exception {
        Long userId = userId(http);
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "leere Datei"));
        }
        String original = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String extension = original.contains(".") ? original.substring(original.lastIndexOf('.')) : "";
        Path dir = filesDir.resolve(String.valueOf(LocalDate.now().getYear()));
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID() + extension);
        file.transferTo(target);

        // Bilder -> Vision, Audio -> Whisper, alles andere (PDF, Word, ...) -> Tika
        String contentType = file.getContentType() == null ? "" : file.getContentType();
        Item.Type type = contentType.startsWith("image/") ? Item.Type.IMAGE
                : contentType.startsWith("audio/") || contentType.startsWith("video/")
                        ? Item.Type.AUDIO : Item.Type.FILE;
        Item item = new Item(userId, type);
        item.setTitle(title != null && !title.isBlank() ? title : original);
        item.setFilePath(target.toString());
        items.save(item);
        pipeline.process(item.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(item));
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<?> getItem(@PathVariable Long id, HttpServletRequest http) {
        return items.findByIdAndUserId(id, userId(http))
                .<ResponseEntity<?>>map(item -> ResponseEntity.ok(toResponse(item)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/items")
    public List<Map<String, Object>> listItems(HttpServletRequest http) {
        return items.findTop20ByUserIdOrderByCreatedAtDesc(userId(http)).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/search")
    public List<EmbeddingService.SearchHit> search(@RequestParam("q") String query,
                                                   @RequestParam(value = "limit", defaultValue = "10") int limit,
                                                   HttpServletRequest http) {
        return embeddings.search(userId(http), query, Math.clamp(limit, 1, 50));
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(TokenAuthFilter.USER_ID_ATTRIBUTE);
    }

    private Item.Type resolveType(String requested, boolean hasUrl) {
        if (requested != null) {
            try {
                return Item.Type.valueOf(requested.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return hasUrl ? Item.Type.WEBPAGE : Item.Type.TEXT;
    }

    private Map<String, Object> toResponse(Item item) {
        String categoryName = item.getCategoryId() == null ? null
                : categories.findById(item.getCategoryId()).map(c -> c.getName()).orElse(null);
        java.util.HashMap<String, Object> map = new java.util.HashMap<>();
        map.put("id", item.getId());
        map.put("type", item.getType().name());
        map.put("status", item.getStatus().name());
        map.put("title", item.getTitle());
        map.put("url", item.getSourceUrl());
        map.put("category", categoryName);
        map.put("confidence", item.getCategoryConfidence());
        map.put("createdAt", item.getCreatedAt().toString());
        return map;
    }
}
