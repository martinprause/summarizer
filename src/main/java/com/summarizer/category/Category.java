package com.summarizer.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    /** Beschreibung dient dem LLM als Klassifikations-Anweisung. */
    private String description;

    private String color;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "parent_id")
    private Long parentId;

    /** FAVORITES = Standard-Favoriten-Kategorie, wird nicht automatisch befüllt. */
    @Column(name = "system_type")
    private String systemType;

    protected Category() {
    }

    public Category(Long userId, String name, String description) {
        this.userId = userId;
        this.name = name;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getSystemType() {
        return systemType;
    }

    public void setSystemType(String systemType) {
        this.systemType = systemType;
    }

    public boolean isFavorites() {
        return "FAVORITES".equals(systemType);
    }

    /** Standard-Kategorie ("Privat") — nicht löschbar, dient als Auffangbecken. */
    public boolean isDefaultCategory() {
        return "DEFAULT".equals(systemType);
    }

    public boolean isSystemCategory() {
        return systemType != null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Category c && id != null && id.equals(c.id);
    }

    @Override
    public int hashCode() {
        return id == null ? System.identityHashCode(this) : id.hashCode();
    }
}
