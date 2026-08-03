package com.summarizer.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Persistenter Chat-Verlauf pro User.
 */
@Service
public class ChatHistoryService {

    private final JdbcTemplate jdbc;

    public ChatHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Message(String role, String text) {
    }

    public List<Message> lastMessages(Long userId, int limit) {
        List<Message> messages = jdbc.query("""
                SELECT role, text FROM chat_messages
                WHERE user_id = ? ORDER BY created_at DESC LIMIT ?
                """, (rs, i) -> new Message(rs.getString("role"), rs.getString("text")),
                userId, limit);
        return messages.reversed();
    }

    public void save(Long userId, String role, String text) {
        jdbc.update("INSERT INTO chat_messages (user_id, role, text) VALUES (?, ?, ?)",
                userId, role, text);
    }

    public void clear(Long userId) {
        jdbc.update("DELETE FROM chat_messages WHERE user_id = ?", userId);
    }
}
