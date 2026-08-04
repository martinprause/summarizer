package com.summarizer.base;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-Memory-Fortschritt für Hintergrund-Jobs (Neu-Kategorisieren,
 * Re-Vektorisieren, Graph-Aufbau). Views pollen per UI-Poll und zeigen
 * ProgressBars an.
 */
@Service
public class JobProgressService {

    public record Progress(String label, int total, int done, boolean running, String result) {

        public double fraction() {
            return total <= 0 ? 0 : Math.min(1.0, (double) done / total);
        }
    }

    private final Map<String, Progress> jobs = new ConcurrentHashMap<>();

    public static String reclassifyKey(Long userId) {
        return "reclassify-" + userId;
    }

    public static String graphKey(Long userId) {
        return "graph-" + userId;
    }

    public static String reembedKey() {
        return "reembed";
    }

    public static String resummarizeKey(Long userId) {
        return "resummarize-" + userId;
    }

    public boolean isRunning(String key) {
        Progress progress = jobs.get(key);
        return progress != null && progress.running();
    }

    public void start(String key, String label, int total) {
        jobs.put(key, new Progress(label, total, 0, true, null));
    }

    public void update(String key, int done) {
        Progress current = jobs.get(key);
        if (current != null) {
            jobs.put(key, new Progress(current.label(), current.total(), done, true, null));
        }
    }

    public void finish(String key, String result) {
        Progress current = jobs.get(key);
        int total = current == null ? 0 : current.total();
        jobs.put(key, new Progress(current == null ? "" : current.label(), total, total, false, result));
    }

    public Optional<Progress> get(String key) {
        return Optional.ofNullable(jobs.get(key));
    }

    /** Irgendein laufender Job — für die globale Statusleiste. */
    public Optional<Progress> anyRunning() {
        return jobs.values().stream().filter(Progress::running).findFirst();
    }
}
