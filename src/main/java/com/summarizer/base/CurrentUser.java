package com.summarizer.base;

import com.summarizer.user.User;
import com.summarizer.user.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Liefert den eingeloggten User. Ist der Login deaktiviert (System-Schalter),
 * laeuft alles unter dem Admin-Konto.
 */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public User get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return users.findByUsername(auth.getName())
                    .orElseThrow(() -> new IllegalStateException("User nicht gefunden: " + auth.getName()));
        }
        // Login deaktiviert: Admin-Konto als Standard
        return users.findByUsername("admin")
                .or(() -> users.findAll().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("Kein User vorhanden"));
    }

    public Long id() {
        return get().getId();
    }
}
