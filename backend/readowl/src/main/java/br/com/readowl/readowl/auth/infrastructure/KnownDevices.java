package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.shared.security.Hashing;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Navegadores em que a conta já entrou com a senha certa. Um ataque de muitos IPs pode esgotar o limite da conta,
 * mas não impede de entrar quem já entrou deste navegador antes.
 */
@Repository
public class KnownDevices {

    private static final int TOKEN_BYTES = 32;
    private static final int MAX_DEVICES_PER_USER = 10;

    private final SecureRandom random = new SecureRandom();
    private final NamedParameterJdbcTemplate jdbc;

    KnownDevices(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isKnown(String token, UUID userId) {
        Boolean known = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM known_devices
                               WHERE token_hash = :hash AND user_id = :userId AND expires_at > now())
                """, Map.of("hash", Hashing.sha256Hex(token), "userId", userId), Boolean.class);
        return Boolean.TRUE.equals(known);
    }

    /**
     * @return o token em claro, que só existe aqui e no cookie
     */
    public String register(UUID userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
                INSERT INTO known_devices (token_hash, user_id, created_at, expires_at)
                VALUES (:hash, :userId, now(), now() + interval '90 days')
                """, Map.of("hash", Hashing.sha256Hex(token), "userId", userId));
        jdbc.update("""
                DELETE FROM known_devices WHERE user_id = :userId AND token_hash NOT IN (
                    SELECT token_hash FROM known_devices WHERE user_id = :userId
                    ORDER BY created_at DESC LIMIT :max)
                """, Map.of("userId", userId, "max", MAX_DEVICES_PER_USER));
        return token;
    }

    public void forgetAll(UUID userId) {
        jdbc.update("DELETE FROM known_devices WHERE user_id = :userId", Map.of("userId", userId));
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    public void purgeExpired() {
        jdbc.update("DELETE FROM known_devices WHERE expires_at < now()", Map.of());
    }
}
