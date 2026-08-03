package com.summarizer.security;

import com.summarizer.security.ui.LoginView;
import com.summarizer.settings.AppSettingsService;
import com.summarizer.user.User;
import com.summarizer.user.UserRepository;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Rein lokale Authentifizierung:
 *  - /api/** schuetzt der TokenAuthFilter (Bearer-Tokens).
 *  - UI: Username/E-Mail + Passwort — ODER komplett ohne Login,
 *    umschaltbar in System → "Login erforderlich" (wirkt nach Neustart).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String LOGIN_ENABLED_KEY = "auth.login-enabled";

    @Bean
    @Order(1)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain vaadinChain(HttpSecurity http, AppSettingsService settings)
            throws Exception {
        boolean loginEnabled = isLoginEnabled(settings);

        // PDF-Vorschau im iframe: Default X-Frame-Options DENY blockt eigene Inhalte
        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        if (!loginEnabled) {
            // Login deaktiviert: alles offen, Vaadin nutzt sein eigenes CSRF-Handling
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable());
            return http.build();
        }

        // Eigene REST-Pfade VOR dem Vaadin-Configurer freigeben — dessen
        // anyRequest-Regel behandelt unbekannte Pfade sonst als "deny" (403)
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/files/**", "/export/**").authenticated());
        return http.with(VaadinSecurityConfigurer.vaadin(), configurer ->
                configurer.loginView(LoginView.class)).build();
    }

    /** Standard: KEIN Login — rein lokaler Betrieb. Aktivierbar in System → Zugriff. */
    public static boolean isLoginEnabled(AppSettingsService settings) {
        try {
            return "true".equals(settings.get(LOGIN_ENABLED_KEY, "false"));
        } catch (Exception e) {
            return false;   // Setting nicht lesbar → Standard ohne Login
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Login mit Benutzername ODER E-Mail; Principal ist immer der kanonische Username. */
    @Bean
    public UserDetailsService userDetailsService(UserRepository users) {
        return input -> {
            User user = users.findByUsername(input)
                    .or(() -> users.findByEmailIgnoreCase(input))
                    .orElseThrow(() -> new UsernameNotFoundException(input));
            if (user.getPasswordHash() == null) {
                throw new UsernameNotFoundException(input);
            }
            return new org.springframework.security.core.userdetails.User(
                    user.getUsername(),
                    user.getPasswordHash(),
                    true, true, true,
                    !user.isLocked(),
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
        };
    }
}
