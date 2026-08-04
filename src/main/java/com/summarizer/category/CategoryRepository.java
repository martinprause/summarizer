package com.summarizer.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByUserIdOrderBySortOrderAscNameAsc(Long userId);

    /** Unterkategorien mehrerer Quellen unter ein neues Elternteil hängen (Merge). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
            UPDATE Category c SET c.parentId = :target
            WHERE c.userId = :userId AND c.parentId IN :sources""")
    int reparentChildren(@org.springframework.data.repository.query.Param("userId") Long userId,
                         @org.springframework.data.repository.query.Param("target") Long target,
                         @org.springframework.data.repository.query.Param("sources") List<Long> sources);
}
