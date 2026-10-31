package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.SqlFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class AccountTokensIntegrationTest {

    @Autowired
    private AccountTokens tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purgeRemovesOnlyTokensExpiredForMoreThanADayAndRunsWithoutACallerTransaction() {
        UUID userId = new SqlFixtures(jdbc).user();
        insertToken("verification_tokens", userId, "velho-v", "2 days");
        insertToken("verification_tokens", userId, "recente-v", "1 hour");
        insertToken("password_reset_tokens", userId, "velho-r", "2 days");

        tokens.purgeExpired();

        assertThat(jdbc.queryForList("SELECT token_hash FROM verification_tokens WHERE user_id = ?", String.class, userId))
                .containsExactly("recente-v");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }

    private void insertToken(String table, UUID userId, String hash, String expiredAgo) {
        jdbc.update("INSERT INTO " + table + " (id, user_id, token_hash, expires_at, created_at) "
                        + "VALUES (?, ?, ?, now() - ?::interval, now())",
                UUID.randomUUID(), userId, hash, expiredAgo);
    }
}
