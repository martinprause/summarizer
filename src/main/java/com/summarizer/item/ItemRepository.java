package com.summarizer.item;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {

    Optional<Item> findByIdAndUserId(Long id, Long userId);

    List<Item> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Item> findFirstByUserIdAndSourceUrl(Long userId, String sourceUrl);

    List<Item> findByUserIdAndStatus(Long userId, Item.Status status);

    long countByUserId(Long userId);

    long countByStatus(Item.Status status);

    /** Anzahl Items je Kategorie (direkt zugeordnet) — für die Sidebar-Zähler. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT i.categoryId, count(i) FROM Item i
            WHERE i.userId = :userId AND i.categoryId IS NOT NULL
            GROUP BY i.categoryId""")
    java.util.List<Object[]> countPerCategory(
            @org.springframework.data.repository.query.Param("userId") Long userId);

    long countByCategoryIdIn(List<Long> categoryIds);
}
