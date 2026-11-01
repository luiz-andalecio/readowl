package br.com.readowl.readowl.moderation;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class DeniedAuditIntegrationTest {

    private static final String DENIED_PATH = "/api/v1/moderation/reports";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private ApiClient user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        mail.setFailing(false);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        user = accounts.loggedIn(email, PASSWORD);
        userId = accounts.userId(email);
    }

    private int entries() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ?", Integer.class, userId);
    }

    @Test
    void aPathLongerThanTheColumnsIsStillRecorded() {
        String path = "/api/v1/admin/" + "a".repeat(600);

        assertThat(user.get(path).status()).isEqualTo(403);

        assertThat(entries()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT length(action) FROM audit_log WHERE actor_id = ?", Integer.class, userId))
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT length(resource) FROM audit_log WHERE actor_id = ?", Integer.class, userId))
                .isEqualTo(500);
    }

    @Test
    void repeatingRefusedRequestsDoesNotFillTheAudit() {
        for (int attempt = 0; attempt < 65; attempt++) {
            assertThat(user.get(DENIED_PATH).status()).isEqualTo(403);
        }

        assertThat(entries()).isEqualTo(60);
    }
}
