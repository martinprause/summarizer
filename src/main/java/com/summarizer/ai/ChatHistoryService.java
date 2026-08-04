package com.summarizer.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persistenter Chat-Verlauf pro User.
 */
@Service
public class ChatHistoryService {

    private final JdbcTemplate jdbc;

    public ChatHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Long userId, String role, String text) {
        jdbc.update("INSERT INTO chat_messages (user_id, role, text) VALUES (?, ?, ?)",
                userId, role, text);
    }

    public void clear(Long userId) {
        jdbc.update("DELETE FROM chat_messages WHERE user_id = ?", userId);
    }
}
