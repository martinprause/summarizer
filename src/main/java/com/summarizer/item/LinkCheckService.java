package com.summarizer.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Prüft alle gespeicherten Links (HEAD, bei 405 GET) und markiert tote Links.
 * Läuft als Hintergrund-Job mit Fortschritt in der Statusleiste.
 */
@Service
public class LinkCheckService {

    private static final Logger log = LoggerFactory.getLogger(LinkCheckService.class);

    private final JdbcTemplate jdbc;
    private final com.summarizer.base.JobProgressService progress;

    public LinkCheckService(JdbcTemplate jdbc, com.summarizer.base.JobProgressService progress) {
        this.jdbc = jdbc;
        this.progress = progress;
    }

    public static String jobKey(Long userId) {
        return "linkcheck-" + userId;
    }

    @Async
    public void checkAll(Long userId) {
        List<Map<String, Object>> links = jdbc.queryForList("""
                SELECT id, source_url FROM items
                WHERE user_id = ? AND source_url IS NOT NULL AND source_url LIKE 'http%'
                ORDER BY id
                """, userId);
        String key = jobKey(userId);
        progress.start(key, "Links prüfen", links.size());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        int done = 0;
        int dead = 0;
        for (Map<String, Object> row : links) {
            long id = ((Number) row.get("id")).longValue();
            boolean ok = isReachable(client, (String) row.get("source_url"));
            jdbc.update("UPDATE items SET link_ok = ? WHERE id = ?", ok, id);
            if (!ok) {
                dead++;
            }
            progress.update(key, ++done);
        }
        progress.finish(key, done + " Links geprüft, " + dead + " tot");
        log.info("Link-Check für User {}: {} geprüft, {} tot", userId, done, dead);
    }

    private boolean isReachable(HttpClient client, String url) {
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 Summarizer-LinkCheck")
                    .build();
            int status = client.send(head, HttpResponse.BodyHandlers.discarding()).statusCode();
            // Manche Server verbieten HEAD (405/403) — dann mit GET nachprüfen
            if (status == 405 || status == 403 || status == 501) {
                HttpRequest get = HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "Mozilla/5.0 Summarizer-LinkCheck")
                        .build();
                status = client.send(get, HttpResponse.BodyHandlers.discarding()).statusCode();
            }
            return status < 400;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;   // DNS-Fehler, Timeout, TLS kaputt -> tot
        }
    }
}
