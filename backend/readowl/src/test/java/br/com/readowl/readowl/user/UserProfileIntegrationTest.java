package br.com.readowl.readowl.user;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class UserProfileIntegrationTest {

    private static final String ME = "/api/v1/users/me";

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

    @Test
    void publicProfileExposesNoPrivateData() {
        String email = uniqueEmail();
        String username = accounts.registerVerified(email);

        ApiClient.Response response = accounts.client().get("/api/v1/users/" + username);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().at("/username").asString()).isEqualTo(username);
        assertThat(response.json().at("/name").asString()).isEqualTo("Pessoa Leitora");
        assertThat(response.body()).doesNotContain(email).doesNotContain("role").doesNotContain("password")
                .doesNotContain("\"id\"").doesNotContain("status");
        assertThat(response.json().at("/createdAt").asString()).endsWith("Z");
    }

    @Test
    void unverifiedAndSuspendedProfilesLookLikeTheyDoNotExist() {
        String unverified = accounts.registerUnverified(uniqueEmail());
        String email = uniqueEmail();
        String suspended = accounts.registerVerified(email);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        ApiClient.Response forUnverified = accounts.client().get("/api/v1/users/" + unverified);

        assertThat(forUnverified.status()).isEqualTo(404);
        assertThat(forUnverified.code()).isEqualTo("USER_NOT_FOUND");
        assertThat(accounts.client().get("/api/v1/users/" + suspended).status()).isEqualTo(404);
        assertThat(accounts.client().get("/api/v1/users/ninguem-aqui").status()).isEqualTo(404);
    }

    @Test
    void ownProfileRequiresLogin() {
        assertThat(accounts.client().get(ME).status()).isEqualTo(401);
        assertThat(accounts.client().patch(ME, body("name", "Outro")).status()).isEqualTo(401);
    }

    @Test
    void ownProfileIncludesPrivateFieldsButNotTheHash() {
        String email = uniqueEmail();
        accounts.registerVerified(email);

        ApiClient.Response response = accounts.loggedIn(email, PASSWORD).get(ME);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().at("/email").asString()).isEqualTo(email);
        assertThat(response.json().at("/emailVerified").asBoolean()).isTrue();
        assertThat(response.body()).doesNotContain("argon2").doesNotContain("passwordHash");
    }

    @Test
    void patchChangesOnlyInformedFields() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient session = accounts.loggedIn(email, PASSWORD);

        ApiClient.Response described = session.patch(ME, body("description", "  Leio de tudo.  "));
        ApiClient.Response renamed = session.patch(ME, body("name", "Novo Nome"));
        ApiClient.Response cleared = session.patch(ME, body("description", " "));

        assertThat(described.json().at("/description").asString()).isEqualTo("Leio de tudo.");
        assertThat(described.json().at("/name").asString()).isEqualTo("Pessoa Leitora");
        assertThat(renamed.json().at("/name").asString()).isEqualTo("Novo Nome");
        assertThat(renamed.json().at("/description").asString()).isEqualTo("Leio de tudo.");
        assertThat(cleared.json().at("/description").isNull()).isTrue();
    }

    @Test
    void patchCannotEscalatePrivilegesOrChangeIdentity() {
        String email = uniqueEmail();
        String username = accounts.registerVerified(email);
        ApiClient session = accounts.loggedIn(email, PASSWORD);

        ApiClient.Response response = session.patch(ME, body(
                "role", "ADMIN", "status", "ACTIVE", "email", "outro@example.com", "username", "outro-nome",
                "emailVerifiedAt", "2020-01-01T00:00:00Z", "passwordHash", "x", "id", "00000000-0000-0000-0000-000000000000"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().at("/role").asString()).isEqualTo("USER");
        assertThat(response.json().at("/email").asString()).isEqualTo(email);
        assertThat(response.json().at("/username").asString()).isEqualTo(username);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, email)).isEqualTo("USER");
    }

    @Test
    void patchValidatesFields() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient session = accounts.loggedIn(email, PASSWORD);

        assertThat(session.patch(ME, body("name", "   ")).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(session.patch(ME, body("name", "x".repeat(101))).status()).isEqualTo(400);
        assertThat(session.patch(ME, body("description", "x".repeat(2001))).status()).isEqualTo(400);
    }

    @Test
    void patchRequiresCsrf() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient session = accounts.loggedIn(email, PASSWORD);

        assertThat(session.postWithoutCsrf(ME, body("name", "Outro")).status()).isEqualTo(403);
    }
}
