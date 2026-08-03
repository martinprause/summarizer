package com.summarizer.token;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Einfaches Token-Bucket-Rate-Limit für /api/** — pro Client-IP.
 * Läuft VOR der Token-Prüfung, bremst damit auch Brute-Force auf Tokens.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS = 60;              // pro Fenster
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_TRACKED_CLIENTS = 10_000;   // Speicher-Schutz

    private record Window(Instant start, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String client = clientKey(request);
        Instant now = Instant.now();

        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.entrySet().removeIf(e -> e.getValue().start().plus(WINDOW).isBefore(now));
        }

        Window window = windows.compute(client, (key, current) ->
                current == null || current.start().plus(WINDOW).isBefore(now)
                        ? new Window(now, new AtomicInteger(0))
                        : current);

        int used = window.count().incrementAndGet();
        long resetSeconds = Duration.between(now, window.start().plus(WINDOW)).toSeconds();
        response.setHeader("X-RateLimit-Limit", String.valueOf(MAX_REQUESTS));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, MAX_REQUESTS - used)));

        if (used > MAX_REQUESTS) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, resetSeconds)));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"rate limit exceeded\",\"retryAfterSeconds\":" + resetSeconds + "}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Echte Client-IP — hinter Tunnel/Proxy steht sie in X-Forwarded-For. */
    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
