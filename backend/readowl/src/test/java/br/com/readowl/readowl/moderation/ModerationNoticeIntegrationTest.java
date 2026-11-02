package br.com.readowl.readowl.moderation;

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
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quem escreve fica sabendo o que saiu do ar e por quê. Quem denuncia fica sabendo o resultado.
 */
@ApiIntegrationTest
class ModerationNoticeIntegrationTest {

    private static final String INBOX = "/api/v1/notifications";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private BookFixtures books;
    private ApiClient author;
    private ApiClient moderator;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
        author = person("USER");
        moderator = person("ADMIN");
    }

    private ApiClient person(String role) {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = ? WHERE email = ?", role, email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private JsonNode latest(ApiClient client) {
        return client.get(INBOX).json().at("/content/0");
    }

    @Test
    void theAuthorLearnsThatTheBookWasHiddenAndWhyAndThatItCameBack() {
        String book = books.publishedBook(author);

        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/hide", body("reason", "Conteúdo copiado de outra obra")).status())
                .isEqualTo(204);

        JsonNode hidden = latest(author);
        assertThat(hidden.at("/type").asString()).isEqualTo("CONTENT_HIDDEN");
        assertThat(hidden.at("/message").asString()).isEqualTo("Conteúdo copiado de outra obra");
        assertThat(hidden.at("/book/id").asString()).isEqualTo(book);
        assertThat(hidden.at("/chapter").isNull()).isTrue();
        assertThat(author.get(INBOX + "/unread-count").json().at("/unread").asLong()).isOne();

        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/restore", body("reason", "Autoria confirmada")).status())
                .isEqualTo(204);

        JsonNode restored = latest(author);
        assertThat(restored.at("/type").asString()).isEqualTo("CONTENT_RESTORED");
        assertThat(restored.at("/message").asString()).isEqualTo("Autoria confirmada");
    }

    @Test
    void theAuthorOfAHiddenCommentIsWarned() {
        String book = books.publishedBook(author);
        ApiClient reader = person("USER");
        String comment = reader.post("/api/v1/books/" + book + "/comments", body("content", "Ofensa")).json().at("/id").asString();

        assertThat(moderator.post("/api/v1/moderation/comments/" + comment + "/hide", body("reason", "Linguagem ofensiva")).status())
                .isEqualTo(204);

        assertThat(latest(reader).at("/message").asString()).isEqualTo("Linguagem ofensiva");
        assertThat(latest(reader).at("/book/id").asString()).isEqualTo(book);
    }

    @Test
    void whoReportedLearnsTheResultButNotTheInternalNote() {
        String book = books.publishedBook(author);
        ApiClient reporter = person("USER");
        String report = reporter.post("/api/v1/reports", body("targetType", "BOOK", "targetId", book, "reason", "ABUSE"))
                .json().at("/id").asString();

        assertThat(moderator.patch("/api/v1/moderation/reports/" + report, body("status", "DISMISSED", "note", "Anotação interna")).status())
                .isEqualTo(200);

        JsonNode notice = latest(reporter);
        assertThat(notice.at("/type").asString()).isEqualTo("REPORT_DISMISSED");
        assertThat(notice.at("/message").isNull()).isTrue();
        assertThat(notice.at("/book").isNull()).isTrue();
    }

    @Test
    void whoModeratesTheirOwnContentIsNotNotified() {
        String book = books.publishedBook(moderator);

        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/hide", body("reason", "Retirada")).status()).isEqualTo(204);

        assertThat(moderator.get(INBOX).json().at("/totalElements").asLong()).isZero();
    }

    @Test
    void aDeletedAccountReceivesNothing() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        UUID id = accounts.userId(email);
        ApiClient leaving = accounts.loggedIn(email, PASSWORD);
        String book = books.publishedBook(author);
        String comment = leaving.post("/api/v1/books/" + book + "/comments", body("content", "Fica")).json().at("/id").asString();
        assertThat(leaving.delete("/api/v1/users/me", body("password", PASSWORD)).status()).isEqualTo(204);

        assertThat(moderator.post("/api/v1/moderation/comments/" + comment + "/hide", body("reason", "Ofensa")).status()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void aNewChapterOfAHiddenBookKeepsDisappearingButTheNoticeStays() {
        String book = books.publishedBook(author);
        ApiClient follower = person("USER");
        UUID followerId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class,
                follower.get("/api/v1/users/me").json().at("/username").asString());
        UUID chapter = jdbc.queryForObject("SELECT id FROM chapters WHERE book_id = ?::uuid LIMIT 1", UUID.class, book);
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, type, book_id, chapter_id, created_at, next_attempt_at, processed_at)
                VALUES (?, 'CHAPTER_FIRST_PUBLISHED', ?::uuid, ?, now(), now(), now())
                """, event, book, chapter);
        jdbc.update("""
                INSERT INTO notifications (id, user_id, type, book_id, chapter_id, event_id, created_at)
                VALUES (uuidv7(), ?, 'NEW_CHAPTER', ?::uuid, ?, ?, now())
                """, followerId, book, chapter, event);
        assertThat(follower.get(INBOX).json().at("/totalElements").asLong()).isOne();

        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/hide", body("reason", "Análise")).status()).isEqualTo(204);

        assertThat(follower.get(INBOX).json().at("/totalElements").asLong()).isZero();
        assertThat(follower.get(INBOX + "/unread-count").json().at("/unread").asLong()).isZero();
        assertThat(author.get(INBOX).json().at("/totalElements").asLong()).isOne();
    }
}
