package br.com.readowl.readowl.user;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.registration;
import static br.com.readowl.readowl.support.AccountFixtures.unique;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class AdminToolsIntegrationTest {

    private static final String USERS = "/api/v1/admin/users";
    private static final String EVENTS = "/api/v1/admin/outbox-events";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private ApiClient admin;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        mail.setFailing(false);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        admin = accounts.loggedIn(email, PASSWORD);
    }

    @Test
    void administrationFindsAnAccountByTheStartOfTheEmailOrUsername() {
        String email = uniqueEmail();
        String username = accounts.registerVerified(email);

        assertThat(admin.get(USERS + "?query=" + email.substring(0, 12).toUpperCase()).json().at("/content/0/email").asString()).isEqualTo(email);
        assertThat(admin.get(USERS + "?query=" + username).json().at("/totalElements").asLong()).isOne();
        assertThat(admin.get(USERS + "?query=sem-nenhuma-conta-assim").json().at("/totalElements").asLong()).isZero();
    }

    @Test
    void wildcardsInTheSearchAreOnlyText() {
        accounts.registerVerified(uniqueEmail());

        assertThat(admin.get(USERS + "?query=%25").json().at("/totalElements").asLong()).isZero();
        assertThat(admin.get(USERS + "?query=_").json().at("/totalElements").asLong()).isZero();
    }

    @Test
    void aFailedEventCanBeSeenAndRetriedByAdministrationOnly() {
        ApiClient author;
        String email = uniqueEmail();
        accounts.registerVerified(email);
        author = accounts.loggedIn(email, PASSWORD);
        BookFixtures books = new BookFixtures(jdbc);
        String book = books.publishedBook(author);
        UUID chapter = jdbc.queryForObject("SELECT id FROM chapters WHERE book_id = ?::uuid LIMIT 1", UUID.class, book);
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, type, book_id, chapter_id, created_at, next_attempt_at, attempts, failed_at, last_error)
                VALUES (?, 'CHAPTER_FIRST_PUBLISHED', ?::uuid, ?, now(), now(), 3, now(), 'Falha')
                """, event, book, chapter);

        assertThat(author.get(EVENTS).status()).isEqualTo(403);
        assertThat(admin.get(EVENTS).json().at("/content").toString()).contains(event.toString()).contains("Falha");

        assertThat(admin.post(EVENTS + "/" + event + "/retry", body()).status()).isEqualTo(204);

        assertThat(jdbc.queryForMap("SELECT failed_at, attempts, last_error FROM outbox_events WHERE id = ?", event))
                .containsEntry("failed_at", null).containsEntry("attempts", 0).containsEntry("last_error", null);
        assertThat(admin.post(EVENTS + "/" + event + "/retry", body()).code()).isEqualTo("FAILED_EVENT_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "PASSWORD123", "senha123", "flamengo", "aaaaaaaa", "abababab"})
    void aVeryCommonPasswordIsRefusedInTheRegistration(String password) {
        var refused = accounts.client().post("/api/v1/auth/register", registration(unique(), uniqueEmail(), password));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("PASSWORD_TOO_COMMON");
    }

    @Test
    void aVeryCommonPasswordIsRefusedWhenChangingAndResetting() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);

        assertThat(client.put("/api/v1/users/me/password", body("currentPassword", PASSWORD, "newPassword", "12345678")).code())
                .isEqualTo("PASSWORD_TOO_COMMON");
        assertThat(accounts.client().post("/api/v1/auth/password/reset", body("token", "qualquer", "newPassword", "12345678")).code())
                .isEqualTo("PASSWORD_TOO_COMMON");
    }
}
