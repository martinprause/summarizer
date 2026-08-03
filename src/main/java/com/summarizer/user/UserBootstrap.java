package com.summarizer.user;

import com.summarizer.category.Category;
import com.summarizer.category.CategoryRepository;
import com.summarizer.token.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.List;

/**
 * Legt beim allerersten Start einen Admin-User, Beispiel-Kategorien und
 * ein initiales API-Token an. Das Token wird einmalig geloggt —
 * ab Phase 2 übernimmt die Settings-View die Token-Verwaltung.
 */
@Component
public class UserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    private final UserRepository users;
    private final CategoryRepository categories;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public UserBootstrap(UserRepository users, CategoryRepository categories, TokenService tokens,
                         PasswordEncoder passwordEncoder,
                         @Value("${summarizer.admin-password:}") String adminPassword) {
        this.users = users;
        this.categories = categories;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureAdminPassword();
        if (users.count() > 0) {
            return;
        }
        User admin = new User("admin", "ADMIN");
        admin.setPasswordHash(passwordEncoder.encode(effectiveAdminPassword()));
        admin = users.save(admin);

        categories.saveAll(List.of(
                new Category(admin.getId(), "Technik",
                        "Software, Programmierung, Hardware, IT-Nachrichten und Tools"),
                new Category(admin.getId(), "Wissen",
                        "Artikel, Studien, Anleitungen und Nachschlagewerke aller Art"),
                defaultCategory(admin.getId())));

        log.info("Admin-Passwort{}: {}", adminPassword.isBlank() ? " (generiert)" : " (aus ENV)",
                adminPassword.isBlank() ? generatedPassword : "****");

        TokenService.CreatedToken initial = tokens.createToken(admin.getId(), "initial");
        log.info("""

                ==========================================================
                 Erststart: Admin-User 'admin' angelegt.
                 Initiales API-Token (nur jetzt sichtbar!):

                   {}

                 Beispiel:
                   curl -H "Authorization: Bearer <TOKEN>" \\
                        -H "Content-Type: application/json" \\
                        -d '{"text":"Hallo Welt"}' \\
                        http://localhost:8080/api/v1/items
                ==========================================================
                """, initial.plaintext());
    }

    /** "Privat" ist die geschuetzte Standard-Kategorie jedes Users. */
    private Category defaultCategory(Long userId) {
        Category privat = new Category(userId, "Privat",
                "Persönliches: Rezepte, Reisen, Einkäufe, Familie, Hobby");
        privat.setSystemType("DEFAULT");
        privat.setColor("#7b1fa2");
        return privat;
    }

    private String generatedPassword;

    private String effectiveAdminPassword() {
        if (!adminPassword.isBlank()) {
            return adminPassword;
        }
        if (generatedPassword == null) {
            byte[] bytes = new byte[9];
            new SecureRandom().nextBytes(bytes);
            generatedPassword = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        return generatedPassword;
    }

    /** Bestehende Installationen (vor Phase 4): Admin ohne Passwort bekommt eins. */
    private void ensureAdminPassword() {
        users.findByUsername("admin").ifPresent(admin -> {
            if (admin.getPasswordHash() == null) {
                String password = effectiveAdminPassword();
                admin.setPasswordHash(passwordEncoder.encode(password));
                users.save(admin);
                log.info("""

                        ==========================================================
                         Login aktiviert. Admin-Zugang:
                           Benutzer: admin
                           Passwort: {}
                         (Passwort ändern: View "Benutzer" im Studio)
                        ==========================================================
                        """, adminPassword.isBlank() ? password : "(wie in ADMIN_PASSWORD gesetzt)");
            }
        });
    }
}
