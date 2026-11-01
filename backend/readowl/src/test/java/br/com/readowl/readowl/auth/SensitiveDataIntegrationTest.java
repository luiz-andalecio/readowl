package br.com.readowl.readowl.auth;

import br.com.readowl.readowl.auth.application.AccountMailType;
import br.com.readowl.readowl.auth.infrastructure.AccountMailQueue.PendingAccountMail;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class SensitiveDataIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        mail.setFailing(false);
    }

    @Test
    void pendingMailDoesNotPrintTheAddress() {
        PendingAccountMail pending = new PendingAccountMail(UUID.randomUUID(), AccountMailType.RESET_PASSWORD, "a@b.com");

        assertThat(pending.toString()).doesNotContain("a@b.com");
    }

    @Test
    void passwordsUseArgon2idWithTheConfiguredCost() {
        String hash = passwordEncoder.encode(PASSWORD);

        assertThat(hash).startsWith("{argon2id}$argon2id$v=19$m=19456,t=2,p=1$").hasSizeLessThan(255);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
        assertThat(passwordEncoder.matches("senha-errada", hash)).isFalse();
    }

    @Test
    void googleLoginIsDisabledWithoutCredentials() {
        assertThat(accounts.client().get("/oauth2/authorization/google").status()).isEqualTo(401);
    }

    @Test
    void linkingGoogleIsUnavailableWithoutCredentials() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient session = accounts.loggedIn(email, PASSWORD);

        ApiClient.Response response = session.post("/api/v1/auth/google/link", body("password", PASSWORD));

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.code()).isEqualTo("GOOGLE_UNAVAILABLE");
    }

    @Test
    void expiredRateLimitBucketsArePurged() {
        jdbc.update("""
                INSERT INTO rate_limit_buckets (bucket_key, window_start, hits, expires_at)
                VALUES ('teste-expirado', now() - interval '2 hours', 1, now() - interval '1 hour')
                """);

        rateLimiter.purgeExpired();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_buckets WHERE bucket_key = 'teste-expirado'",
                Integer.class)).isZero();
    }
}
