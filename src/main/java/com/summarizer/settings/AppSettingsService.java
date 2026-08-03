package com.summarizer.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Key-Value-Systemeinstellungen (LLM-Provider, API-Keys).
 */
@Service
public class AppSettingsService {

    public static final String LLM_PROVIDER = "llm.provider";      // ollama | openai | anthropic
    public static final String LLM_API_KEY = "llm.api-key";
    public static final String LLM_CLOUD_MODEL = "llm.cloud-model";

    private final JdbcTemplate jdbc;

    public AppSettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String get(String key, String fallback) {
        List<String> values = jdbc.queryForList(
                "SELECT value FROM app_settings WHERE key = ?", String.class, key);
        return values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()
                ? fallback : values.getFirst();
    }

    @Transactional
    public void set(String key, String value) {
        jdbc.update("""
                INSERT INTO app_settings (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
    }
}
