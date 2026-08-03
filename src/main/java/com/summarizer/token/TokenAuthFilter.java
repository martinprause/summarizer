package com.summarizer.token;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Schützt alle /api/**-Endpunkte per Bearer-Token.
 * Bei Erfolg liegt die User-ID als Request-Attribut USER_ID_ATTRIBUTE vor.
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTRIBUTE = "summarizer.userId";

    private final TokenService tokenService;

    public TokenAuthFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Optional<ApiToken> token = tokenService.validate(header.substring(7).trim());
            if (token.isPresent()) {
                request.setAttribute(USER_ID_ATTRIBUTE, token.get().getUserId());
                filterChain.doFilter(request, response);
                return;
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"invalid or missing token\"}");
    }
}
