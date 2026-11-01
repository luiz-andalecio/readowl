package br.com.readowl.readowl.auth;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sessões que sobreviveram ao que deveria tê-las encerrado, e sessões que a aplicação não consegue mais ler.
 */
@ApiIntegrationTest
class SessionAccessIntegrationTest {

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

    private String verifiedEmail() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return email;
    }

    @Test
    void aSuspensionThatDidNotEndTheSessionStillBlocksWrites() {
        String email = verifiedEmail();
        ApiClient user = accounts.loggedIn(email, PASSWORD);
        String path = "/api/v1/users/me";
        assertThat(user.patch(path, body("name", "Antes")).status()).isEqualTo(200);

        // Muda direto no banco, como se a revogação depois do commit tivesse falhado.
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        assertThat(user.patch(path, body("name", "Depois")).status()).isEqualTo(401);
        assertThat(user.get(path).status()).as("a sessão foi encerrada").isEqualTo(401);
    }

    @Test
    void aRoleLostWithoutEndingTheSessionNoLongerOpensAdministration() {
        String email = verifiedEmail();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        ApiClient admin = accounts.loggedIn(email, PASSWORD);
        assertThat(admin.get("/api/v1/admin/users").status()).isEqualTo(200);

        jdbc.update("UPDATE users SET role = 'USER' WHERE email = ?", email);

        assertThat(admin.get("/api/v1/admin/users").status()).isEqualTo(401);
    }

    @Test
    void anUnchangedAccountKeepsWorking() {
        ApiClient user = accounts.loggedIn(verifiedEmail(), PASSWORD);

        assertThat(new BookFixtures(jdbc).createBook(user)).isNotBlank();
        assertThat(user.get("/api/v1/users/me").status()).isEqualTo(200);
    }

    @Test
    void aSessionTheApplicationCanNoLongerReadCountsAsLoggedOut() {
        ApiClient user = accounts.loggedIn(verifiedEmail(), PASSWORD);
        String sessionId = new String(Base64.getDecoder().decode(user.cookie("SESSION").orElseThrow()), StandardCharsets.UTF_8);

        // Como uma classe movida ou uma atualização do framework deixariam os atributos gravados.
        jdbc.update("""
                UPDATE spring_session_attributes SET attribute_bytes = decode('00ff00ff', 'hex')
                WHERE session_primary_id = (SELECT primary_id FROM spring_session WHERE session_id = ?)
                """, sessionId);

        assertThat(user.get("/api/v1/users/me").status()).isEqualTo(401);
        assertThat(user.get("/api/v1/books").status()).isEqualTo(200);
    }
}
