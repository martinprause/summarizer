package com.summarizer.category;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Hierarchie-Helfer: Wurzeln, Kinder, Nachfahren (für Filter "Kategorie inkl. Unterkategorien").
 */
@Service
public class CategoryTreeService {

    private final CategoryRepository repository;

    public CategoryTreeService(CategoryRepository repository) {
        this.repository = repository;
    }

    public List<Category> roots(Long userId) {
        return repository.findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                .filter(c -> c.getParentId() == null)
                .toList();
    }

    public List<Category> children(Long userId, Category parent) {
        return repository.findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                .filter(c -> Objects.equals(c.getParentId(), parent.getId()))
                .toList();
    }

    /** IDs der Kategorie selbst plus aller Nachfahren. */
    public List<Long> selfAndDescendantIds(Long userId, Long categoryId) {
        Map<Long, List<Category>> byParent = repository.findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                .filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(Category::getParentId));
        List<Long> result = new ArrayList<>();
        collect(categoryId, byParent, result);
        return result;
    }

    private void collect(Long id, Map<Long, List<Category>> byParent, List<Long> result) {
        result.add(id);
        for (Category child : byParent.getOrDefault(id, List.of())) {
            collect(child.getId(), byParent, result);
        }
    }

    /** Verhindert Zyklen: prüft ob candidate ein Nachfahre von category ist (oder sie selbst). */
    public boolean wouldCreateCycle(Long userId, Category category, Category candidateParent) {
        if (candidateParent == null) {
            return false;
        }
        return selfAndDescendantIds(userId, category.getId()).contains(candidateParent.getId());
    }
}
