package com.summarizer.item.extract;

import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Text-Extraktion aus Dokumenten (PDF, DOCX, PPTX, EPUB, TXT, …) via Apache Tika.
 */
@Component
public class FileTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(FileTextExtractor.class);
    private static final int MAX_CHARS = 100_000;

    private final Tika tika;

    public FileTextExtractor() {
        this.tika = new Tika();
        this.tika.setMaxStringLength(MAX_CHARS);
    }

    public Optional<String> extract(Path file) {
        try {
            String text = tika.parseToString(file);
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.strip());
        } catch (Exception e) {
            log.warn("Tika-Extraktion für {} fehlgeschlagen: {}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
