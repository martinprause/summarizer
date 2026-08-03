package com.summarizer.token;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class TokenService {

    public static final String TOKEN_PREFIX = "sum_";

    private final ApiTokenRepository repository;
    private final SecureRandom random = new SecureRandom();

    public TokenService(ApiTokenRepository repository) {
        this.repository = repository;
    }

    /**
     * Erzeugt ein neues Token. Der Klartext wird nur einmal zurückgegeben,
     * in der DB liegt ausschließlich der SHA-256-Hash.
     */
    @Transactional
    public CreatedToken createToken(Long userId, String name) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String plaintext = TOKEN_PREFIX + HexFormat.of().formatHex(bytes);
        ApiToken token = new ApiToken(userId, name, sha256(plaintext));
        repository.save(token);
        return new CreatedToken(token, plaintext);
    }

    @Transactional
    public Optional<ApiToken> validate(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Optional<ApiToken> token = repository.findByTokenHashAndRevokedFalse(sha256(plaintext));
        token.ifPresent(t -> t.setLastUsedAt(Instant.now()));
        return token;
    }

    @Transactional
    public void revoke(Long tokenId) {
        repository.findById(tokenId).ifPresent(t -> t.setRevoked(true));
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record CreatedToken(ApiToken token, String plaintext) {
    }
}
