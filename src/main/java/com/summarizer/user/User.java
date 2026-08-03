package com.summarizer.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "auth_provider", nullable = false)
    private String authProvider = "LOCAL";

    @Column(name = "google_sub")
    private String googleSub;

    @Column(nullable = false)
    private String role = "USER";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private boolean locked = false;

    @Column(name = "dashboard_sort", nullable = false)
    private String dashboardSort = "DATE,CATEGORY,TYPE";

    @Column(name = "dashboard_view", nullable = false)
    private String dashboardView = "TILES";

    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    protected User() {
    }

    public User(String username, String role) {
        this.username = username;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getGoogleSub() {
        return googleSub;
    }

    public void setGoogleSub(String googleSub) {
        this.googleSub = googleSub;
    }

    public void setAuthProvider(String authProvider) {
        this.authProvider = authProvider;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public String getAuthProvider() {
        return authProvider;
    }

    public String getDashboardSort() {
        return dashboardSort;
    }

    public void setDashboardSort(String dashboardSort) {
        this.dashboardSort = dashboardSort;
    }

    public String getDashboardView() {
        return dashboardView;
    }

    public void setDashboardView(String dashboardView) {
        this.dashboardView = dashboardView;
    }

    public Long getTelegramChatId() {
        return telegramChatId;
    }

    public void setTelegramChatId(Long telegramChatId) {
        this.telegramChatId = telegramChatId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
