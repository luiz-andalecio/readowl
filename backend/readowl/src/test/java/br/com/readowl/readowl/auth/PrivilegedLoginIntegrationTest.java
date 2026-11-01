package br.com.readowl.readowl.auth;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiTestConfiguration;
import br.com.readowl.readowl.support.RecordingMailSender;
import br.com.readowl.readowl.support.TestDatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;


import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Como em produção: contas com papel de moderação ou administração só entram pelo Google.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "readowl.privileged-google-login-required=true")
@ActiveProfiles("test")
@Import({TestDatabaseConfiguration.class, ApiTestConfiguration.class})
class PrivilegedLoginIntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";

    @LocalServerPort
    private int port;

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

    @ParameterizedTest
    @ValueSource(strings = {"MODERATOR", "ADMIN", "ROOT"})
    void aPrivilegedAccountCannotEnterWithThePasswordAlone(String role) {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = ? WHERE email = ?", role, email);

        ApiClient client = accounts.client();
        var refused = client.post(LOGIN, body("email", email, "password", PASSWORD));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("GOOGLE_LOGIN_REQUIRED");
        assertThat(client.get("/api/v1/users/me").status()).isEqualTo(401);
    }

    @Test
    void theWrongPasswordDoesNotRevealThatTheAccountIsPrivileged() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = 'ROOT' WHERE email = ?", email);

        var refused = accounts.client().post(LOGIN, body("email", email, "password", "errada-123"));

        assertThat(refused.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void aRegularAccountKeepsEnteringWithThePassword() {
        String email = uniqueEmail();
        accounts.registerVerified(email);

        assertThat(accounts.loggedIn(email, PASSWORD).get("/api/v1/users/me").status()).isEqualTo(200);
    }
}
