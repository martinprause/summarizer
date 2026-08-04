package com.summarizer.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByUserIdOrderByDueDateAscIdAsc(Long userId);

    List<Task> findByUserIdAndStatusNotOrderByDueDateAscIdAsc(Long userId, Task.Status status);

    Optional<Task> findByIdAndUserId(Long id, Long userId);
}
