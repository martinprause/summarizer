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

    /**
     * Quell-Kategorien im Ziel aufgehen lassen: Inhalte und Unterkategorien
     * wandern zum Ziel, Quellen werden gelöscht. Beschreibungen optional angehängt.
     */
    @org.springframework.transaction.annotation.Transactional
    public MergeResult mergeInto(Long userId, Long targetId, List<Long> sourceIds,
                                 com.summarizer.item.ItemRepository items,
                                 boolean appendDescriptions) {
        List<Long> sources = sourceIds.stream()
                .filter(id -> !id.equals(targetId))
                .toList();
        if (sources.isEmpty()) {
            return new MergeResult(0, 0);
        }
        int movedItems = items.reassignCategories(userId, targetId, sources);
        int movedChildren = repository.reparentChildren(userId, targetId, sources);
        if (appendDescriptions) {
            repository.findById(targetId).ifPresent(target -> {
                StringBuilder description = new StringBuilder(
                        target.getDescription() == null ? "" : target.getDescription());
                for (Long id : sources) {
                    repository.findById(id).ifPresent(source -> {
                        if (source.getDescription() != null && !source.getDescription().isBlank()
                                && !description.toString().contains(source.getDescription())) {
                            if (!description.isEmpty()) {
                                description.append("; ");
                            }
                            description.append(source.getDescription());
                        }
                    });
                }
                target.setDescription(description.toString());
                repository.save(target);
            });
        }
        repository.deleteAllById(sources);
        return new MergeResult(movedItems, movedChildren);
    }

    public record MergeResult(int movedItems, int movedChildren) {
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
