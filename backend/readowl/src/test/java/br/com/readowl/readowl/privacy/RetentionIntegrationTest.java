package br.com.readowl.readowl.privacy;

import br.com.readowl.readowl.privacy.application.RetentionTasks;
import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ApiIntegrationTest
class RetentionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetentionTasks retention;

    private AccountFixtures accounts;
    private BookFixtures books;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
    }

    private ApiClient person() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private UUID comment(ApiClient author, String book, String text) {
        return UUID.fromString(author.post("/api/v1/books/" + book + "/comments", body("content", text)).json().at("/id").asString());
    }

    private String contentOf(UUID comment) {
        return jdbc.queryForObject("SELECT content FROM comments WHERE id = ?", String.class, comment);
    }

    private int count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Integer.class, parameters);
    }

    @Test
    void anUnconfirmedAccountLeavesAfterTheDeadlineAndFreesTheUsername() {
        String expired = uniqueEmail();
        String username = accounts.registerUnverified(expired);
        String recent = uniqueEmail();
        accounts.registerUnverified(recent);
        jdbc.update("UPDATE users SET created_at = now() - interval '8 days' WHERE email = ?", expired);

        retention.run();

        assertThat(count("SELECT count(*) FROM users WHERE email = ?", expired)).isZero();
        assertThat(count("SELECT count(*) FROM users WHERE username = ?", username)).isZero();
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", recent)).isOne();
    }

    @Test
    void aConfirmedAccountIsNeverRemovedByAge() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET created_at = now() - interval '5 years' WHERE email = ?", email);

        retention.run();

        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isOne();
    }

    @Test
    void anAccountWithDataIsSkippedWithoutStoppingTheOthers() {
        String withData = uniqueEmail();
        accounts.registerVerified(withData);
        UUID id = accounts.userId(withData);
        books.createBook(accounts.loggedIn(withData, PASSWORD));
        jdbc.update("UPDATE users SET email_verified_at = NULL, created_at = now() - interval '8 days' WHERE id = ?", id);
        String empty = uniqueEmail();
        accounts.registerUnverified(empty);
        jdbc.update("UPDATE users SET created_at = now() - interval '8 days' WHERE email = ?", empty);

        retention.run();

        assertThat(count("SELECT count(*) FROM users WHERE id = ?", id)).isOne();
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", empty)).isZero();
    }

    @Test
    void theTextOfADeletedCommentGoesAfterTheDeadlineUnlessThereIsAnOpenReport() {
        ApiClient author = person();
        String book = books.publishedBook(author);
        ApiClient reader = person();
        UUID recent = comment(reader, book, "Excluído há pouco");
        UUID old = comment(reader, book, "Excluído há muito");
        UUID reported = comment(reader, book, "Excluído com denúncia");
        UUID kept = comment(reader, book, "Nunca excluído");
        assertThat(person().post("/api/v1/reports", body("targetType", "COMMENT", "targetId", reported, "reason", "ABUSE")).status()).isEqualTo(201);
        jdbc.update("UPDATE comments SET deleted_at = now() WHERE id = ?", recent);
        jdbc.update("UPDATE comments SET deleted_at = now() - interval '31 days' WHERE id IN (?, ?)", old, reported);
        jdbc.update("UPDATE comments SET created_at = now() - interval '5 years' WHERE id = ?", kept);

        retention.run();

        assertThat(contentOf(recent)).isEqualTo("Excluído há pouco");
        assertThat(contentOf(old)).isEqualTo("[texto removido]");
        assertThat(contentOf(reported)).isEqualTo("Excluído com denúncia");
        assertThat(contentOf(kept)).isEqualTo("Nunca excluído");
    }

    @Test
    void aCommentHiddenByModerationCannotBeRestoredAfterItsTextIsGone() {
        ApiClient author = person();
        String book = books.publishedBook(author);
        UUID comment = comment(person(), book, "Ocultado");
        String moderatorEmail = uniqueEmail();
        accounts.registerVerified(moderatorEmail);
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE email = ?", moderatorEmail);
        ApiClient moderator = accounts.loggedIn(moderatorEmail, PASSWORD);
        assertThat(moderator.post("/api/v1/moderation/comments/" + comment + "/hide", body("reason", "Ofensa")).status()).isEqualTo(204);
        jdbc.update("UPDATE comments SET deleted_at = now() - interval '31 days' WHERE id = ?", comment);

        retention.run();

        var refused = moderator.post("/api/v1/moderation/comments/" + comment + "/restore", body("reason", "Engano"));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("COMMENT_CONTENT_PURGED");
    }

    @Test
    void closedReportsLeaveAfterTheDeadlineAndOpenOnesStay() {
        ApiClient author = person();
        String book = books.publishedBook(author);
        UUID open = UUID.fromString(person().post("/api/v1/reports", body("targetType", "BOOK", "targetId", book, "reason", "ABUSE")).json().at("/id").asString());
        UUID recentlyClosed = UUID.fromString(person().post("/api/v1/reports", body("targetType", "BOOK", "targetId", book, "reason", "SPAM")).json().at("/id").asString());
        UUID longClosed = UUID.fromString(person().post("/api/v1/reports", body("targetType", "BOOK", "targetId", book, "reason", "OTHER")).json().at("/id").asString());
        jdbc.update("UPDATE reports SET created_at = now() - interval '5 years' WHERE id = ?", open);
        jdbc.update("UPDATE reports SET status = 'DISMISSED', resolved_at = now() WHERE id = ?", recentlyClosed);
        jdbc.update("UPDATE reports SET status = 'DISMISSED', resolved_at = now() - interval '366 days' WHERE id = ?", longClosed);

        retention.run();

        assertThat(count("SELECT count(*) FROM reports WHERE id = ?", open)).isOne();
        assertThat(count("SELECT count(*) FROM reports WHERE id = ?", recentlyClosed)).isOne();
        assertThat(count("SELECT count(*) FROM reports WHERE id = ?", longClosed)).isZero();
    }

    @Test
    void aBookKeptForAReportLeavesWhenTheReportCloses() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        UUID authorId = accounts.userId(email);
        ApiClient author = accounts.loggedIn(email, PASSWORD);
        String book = books.publishedBook(author);
        UUID report = UUID.fromString(person().post("/api/v1/reports", body("targetType", "BOOK", "targetId", book, "reason", "ABUSE")).json().at("/id").asString());
        assertThat(author.delete("/api/v1/users/me", body("password", PASSWORD)).status()).isEqualTo(204);

        retention.run();
        assertThat(count("SELECT count(*) FROM books WHERE author_id = ?", authorId)).isOne();

        jdbc.update("UPDATE reports SET status = 'RESOLVED', resolved_at = now() WHERE id = ?", report);
        retention.run();
        assertThat(count("SELECT count(*) FROM books WHERE author_id = ?", authorId)).isZero();
    }

    @Test
    void oldNotificationsAuditEntriesAndAccessRecordsLeave() {
        ApiClient author = person();
        String email = uniqueEmail();
        accounts.registerVerified(email);
        UUID id = accounts.userId(email);
        String chapterBook = books.publishedBook(author);
        UUID chapter = jdbc.queryForObject("SELECT id FROM chapters WHERE book_id = ?::uuid LIMIT 1", UUID.class, chapterBook);
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, type, book_id, chapter_id, created_at, next_attempt_at)
                VALUES (?, 'CHAPTER_FIRST_PUBLISHED', ?::uuid, ?, now(), now() + interval '1 year')
                """, event, chapterBook, chapter);
        jdbc.update("""
                INSERT INTO notifications (id, user_id, type, book_id, chapter_id, event_id, created_at)
                VALUES (uuidv7(), ?, 'NEW_CHAPTER', ?::uuid, ?, ?, now() - interval '366 days')
                """, id, chapterBook, chapter, event);
        jdbc.update("""
                INSERT INTO audit_log (id, actor_id, action, resource, outcome, created_at)
                VALUES (uuidv7(), ?, 'ANTIGA', 'x', 'SUCCESS', now() - interval '731 days'),
                       (uuidv7(), ?, 'RECENTE', 'x', 'SUCCESS', now() - interval '100 days')
                """, id, id);
        jdbc.update("""
                INSERT INTO access_records (id, user_id, ip, action, created_at)
                VALUES (uuidv7(), ?, '1.2.3.4', 'ANTIGO', now() - interval '184 days'),
                       (uuidv7(), ?, '1.2.3.4', 'RECENTE', now() - interval '100 days')
                """, id, id);

        retention.run();

        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", id)).isZero();
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE actor_id = ?", String.class, id)).containsExactly("RECENTE");
        assertThat(jdbc.queryForList("SELECT action FROM access_records WHERE user_id = ? AND ip = '1.2.3.4'", String.class, id)).containsExactly("RECENTE");
    }

    @Test
    void theDatabaseRefusesShortPeriodsForAuditAndAccessRecords() {
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT purge_audit_log(interval '1 day')", Long.class))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT purge_access_records(interval '1 day')", Long.class))
                .isInstanceOf(DataAccessException.class);
    }
}
