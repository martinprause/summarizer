package com.summarizer.category;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Standard-Kategorie "Favoriten": existiert für jeden User, wird nie
 * automatisch befüllt (Klassifikation schließt den Teilbaum aus).
 */
@Service
public class FavoritesService {

    private final CategoryRepository categories;
    private final CategoryTreeService tree;

    public FavoritesService(CategoryRepository categories, CategoryTreeService tree) {
        this.categories = categories;
        this.tree = tree;
    }

    @Transactional
    public Category ensureExists(Long userId) {
        return find(userId).orElseGet(() -> {
            Category favorites = new Category(userId, "Favoriten", "Manuell markierte Favoriten");
            favorites.setColor("#f9a825");
            favorites.setSortOrder(-1);
            favorites.setSystemType("FAVORITES");
            return categories.save(favorites);
        });
    }

    public Optional<Category> find(Long userId) {
        return categories.findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                .filter(Category::isFavorites)
                .findFirst();
    }

    /** IDs von Favoriten + allen Unterkategorien. */
    public List<Long> subtreeIds(Long userId) {
        return find(userId)
                .map(f -> tree.selfAndDescendantIds(userId, f.getId()))
                .orElse(List.of());
    }
}
