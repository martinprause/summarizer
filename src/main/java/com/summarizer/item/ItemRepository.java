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

    long countByCategoryIdIn(List<Long> categoryIds);
}
