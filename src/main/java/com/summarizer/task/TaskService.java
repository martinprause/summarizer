package com.summarizer.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Aufgaben + Verknüpfung zu Inhalten (item_tasks).
 */
@Service
public class TaskService {

    private final JdbcTemplate jdbc;
    private final TaskRepository tasks;

    public TaskService(JdbcTemplate jdbc, TaskRepository tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    public void linkItem(Long itemId, Long taskId) {
        jdbc.update("""
                INSERT INTO item_tasks (item_id, task_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, itemId, taskId);
    }

    public void unlinkItem(Long itemId, Long taskId) {
        jdbc.update("DELETE FROM item_tasks WHERE item_id = ? AND task_id = ?", itemId, taskId);
    }

    /** Aufgaben eines Inhalts. */
    public List<Task> tasksForItem(Long itemId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT task_id FROM item_tasks WHERE item_id = ?", Long.class, itemId);
        return ids.isEmpty() ? List.of() : tasks.findAllById(ids);
    }

    /** Verknüpfte Inhalte einer Aufgabe (id, title, type). */
    public List<Map<String, Object>> itemsForTask(Long taskId) {
        return jdbc.queryForList("""
                SELECT i.id, i.title, i.type FROM item_tasks it
                JOIN items i ON i.id = it.item_id
                WHERE it.task_id = ? ORDER BY i.created_at DESC
                """, taskId);
    }

    /** Anzahl verknüpfter Inhalte je Aufgabe eines Users. */
    public Map<Long, Long> itemCounts(Long userId) {
        var result = new java.util.HashMap<Long, Long>();
        jdbc.query("""
                SELECT it.task_id, count(*) AS n FROM item_tasks it
                JOIN tasks t ON t.id = it.task_id
                WHERE t.user_id = ? GROUP BY it.task_id
                """, rs -> {
            result.put(rs.getLong("task_id"), rs.getLong("n"));
        }, userId);
        return result;
    }
}
