package com.summarizer.item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "items")
public class Item {

    public enum Type { TEXT, IMAGE, BOOKMARK, WEBPAGE, FILE, AUDIO }

    public enum Status { PENDING, PROCESSING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    private String title;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "raw_text")
    private String rawText;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "category_confidence")
    private Float categoryConfidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt = Instant.now();

    @Column(nullable = false)
    private boolean favorite = false;

    private String summary;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "snapshot_path")
    private String snapshotPath;

    /** NULL = ungeprüft, TRUE = erreichbar, FALSE = toter Link. */
    @Column(name = "link_ok")
    private Boolean linkOk;

    protected Item() {
    }

    public Item(Long userId, Type type) {
        this.userId = userId;
        this.type = type;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Type getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Float getCategoryConfidence() {
        return categoryConfidence;
    }

    public void setCategoryConfidence(Float categoryConfidence) {
        this.categoryConfidence = categoryConfidence;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public String getSnapshotPath() {
        return snapshotPath;
    }

    public void setSnapshotPath(String snapshotPath) {
        this.snapshotPath = snapshotPath;
    }

    public Boolean getLinkOk() {
        return linkOk;
    }

    public void setLinkOk(Boolean linkOk) {
        this.linkOk = linkOk;
    }
}
