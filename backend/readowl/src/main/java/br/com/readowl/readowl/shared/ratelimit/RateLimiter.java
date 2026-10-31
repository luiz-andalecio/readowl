package br.com.readowl.readowl.shared.ratelimit;

import br.com.readowl.readowl.shared.exception.RateLimitExceededException;
import br.com.readowl.readowl.shared.security.Hashing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Janela fixa no PostgreSQL, compartilhada entre instâncias. O sujeito é guardado como hash para que
 * a tabela não acumule e-mails e IPs. Deve ser chamado fora de transação: a tentativa precisa contar
 * mesmo quando a operação falha.
 */
@Component
public class RateLimiter {

    private static final String HIT = """
            INSERT INTO rate_limit_buckets (bucket_key, window_start, hits, expires_at)
            SELECT ?, w.start, 1, w.start + make_interval(secs => ?)
            FROM (SELECT to_timestamp(floor(extract(epoch FROM now()) / ?) * ?) AS start) w
            ON CONFLICT (bucket_key, window_start) DO UPDATE SET hits = rate_limit_buckets.hits + 1
            RETURNING hits, ceil(extract(epoch FROM expires_at - now()))::bigint AS retry_after
            """;

    private final JdbcTemplate jdbc;

    public RateLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void acquire(RateLimitPolicy policy, String subject) {
        String key = policy.name() + ":" + Hashing.sha256Hex(subject);
        long seconds = policy.getWindow().toSeconds();
        Map<String, Object> bucket = jdbc.queryForMap(HIT, key, seconds, seconds, seconds);
        if (((Number) bucket.get("hits")).intValue() > policy.getLimit()) {
            throw new RateLimitExceededException(((Number) bucket.get("retry_after")).longValue());
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    public void purgeExpired() {
        jdbc.update("DELETE FROM rate_limit_buckets WHERE expires_at < now()");
    }
}
