package com.summarizer.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByGoogleSub(String googleSub);

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByTelegramChatId(Long telegramChatId);
}
