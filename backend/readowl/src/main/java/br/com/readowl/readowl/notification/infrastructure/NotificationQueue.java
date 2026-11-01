package br.com.readowl.readowl.notification.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Filas do worker em SQL. FOR UPDATE SKIP LOCKED permite várias instâncias sem que duas peguem o mesmo item.
 */
@Repository
public class NotificationQueue {

    private static final String RECORD_EVENT_FAILURE = """
            UPDATE outbox_events
            SET attempts = attempts + 1,
                last_error = :error,
                next_attempt_at = now() + (attempts + 1) * interval '1 minute',
                failed_at = CASE WHEN attempts + 1 >= :maxAttempts THEN now() END
            WHERE id = :id
            """;
    private static final String RECORD_EMAIL_FAILURE = """
            UPDATE notifications
            SET email_attempts = email_attempts + 1,
                email_last_error = :error,
                email_next_attempt_at = now() + (email_attempts + 1) * interval '5 minutes',
                email_status = CASE WHEN email_attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END
            WHERE id = :id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    NotificationQueue(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PendingEmail(UUID id, String to, String bookTitle, String bookSlug, String chapterTitle,
                               String chapterSlug, boolean deliverable) {
    }

    public void enqueueChapterPublished(UUID bookId, UUID chapterId) {
        jdbc.update("""
                INSERT INTO outbox_events (id, type, book_id, chapter_id, created_at, next_attempt_at)
                VALUES (uuidv7(), 'CHAPTER_FIRST_PUBLISHED', :bookId, :chapterId, now(), now())
                """, Map.of("bookId", bookId, "chapterId", chapterId));
    }

    public Optional<UUID> claimNextEvent() {
        return jdbc.query("""
                SELECT id FROM outbox_events
                WHERE processed_at IS NULL AND failed_at IS NULL AND next_attempt_at <= now()
                ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }

    /**
     * Um único comando atende qualquer número de seguidores. A restrição (user_id, event_id) torna o
     * reprocessamento seguro. Se o capítulo saiu do ar nesse meio tempo, ninguém é notificado.
     */
    public void fanOut(UUID eventId) {
        // Obras muito seguidas podem levar mais que o tempo máximo normal de um comando.
        jdbc.update("SET LOCAL statement_timeout = 0", Map.of());
        jdbc.update("""
                INSERT INTO notifications (id, user_id, type, book_id, chapter_id, event_id, created_at,
                                           email_status, email_next_attempt_at)
                SELECT uuidv7(), f.user_id, 'NEW_CHAPTER', e.book_id, e.chapter_id, e.id, now(),
                       CASE WHEN u.email_new_chapters THEN 'PENDING' END,
                       CASE WHEN u.email_new_chapters THEN now() END
                FROM outbox_events e
                JOIN books b ON b.id = e.book_id
                JOIN users a ON a.id = b.author_id
                JOIN chapters c ON c.id = e.chapter_id
                JOIN book_follows f ON f.book_id = e.book_id
                JOIN users u ON u.id = f.user_id
                WHERE e.id = :eventId AND b.publication_state = 'PUBLISHED' AND a.status = 'ACTIVE'
                  AND c.state = 'PUBLISHED' AND u.status = 'ACTIVE' AND f.user_id <> b.author_id
                ON CONFLICT (user_id, event_id) DO NOTHING
                """, Map.of("eventId", eventId));
    }

    public void markEventProcessed(UUID eventId) {
        jdbc.update("UPDATE outbox_events SET processed_at = now() WHERE id = :id", Map.of("id", eventId));
    }

    public void recordEventFailure(UUID eventId, String error, int maxAttempts) {
        jdbc.update(RECORD_EVENT_FAILURE, failure(eventId, error, maxAttempts));
    }

    public int purgeProcessedEvents() {
        return jdbc.update("DELETE FROM outbox_events WHERE processed_at < now() - interval '30 days'", Map.of());
    }

    /**
     * Reservar adia a próxima tentativa, em um único comando: ninguém mais pega o e-mail durante o envio, que
     * acontece fora de transação. Se a aplicação cair no meio dele, o prazo vence e outra instância o assume.
     */
    public List<PendingEmail> claimEmails(int limit) {
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM notifications
                    WHERE email_status = 'PENDING' AND email_next_attempt_at <= now()
                    ORDER BY email_next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED
                ), claimed AS (
                    UPDATE notifications n SET email_next_attempt_at = now() + interval '10 minutes'
                    FROM due WHERE n.id = due.id
                    RETURNING n.id, n.user_id, n.book_id, n.chapter_id
                )
                SELECT n.id, u.email, b.title AS book_title, b.slug AS book_slug, c.title AS chapter_title,
                       c.slug AS chapter_slug,
                       u.status = 'ACTIVE' AND u.email_new_chapters AND b.publication_state = 'PUBLISHED'
                           AND a.status = 'ACTIVE' AND c.state = 'PUBLISHED' AS deliverable
                FROM claimed n
                JOIN users u ON u.id = n.user_id
                JOIN books b ON b.id = n.book_id
                JOIN users a ON a.id = b.author_id
                JOIN chapters c ON c.id = n.chapter_id
                """, Map.of("limit", limit), (rs, row) -> new PendingEmail(rs.getObject("id", UUID.class),
                rs.getString("email"), rs.getString("book_title"), rs.getString("book_slug"),
                rs.getString("chapter_title"), rs.getString("chapter_slug"), rs.getBoolean("deliverable")));
    }

    public void markEmail(UUID notificationId, String status) {
        jdbc.update("UPDATE notifications SET email_status = :status WHERE id = :id",
                Map.of("status", status, "id", notificationId));
    }

    public void recordEmailFailure(UUID notificationId, String error, int maxAttempts) {
        jdbc.update(RECORD_EMAIL_FAILURE, failure(notificationId, error, maxAttempts));
    }

    private static MapSqlParameterSource failure(UUID id, String error, int maxAttempts) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("error", error)
                .addValue("maxAttempts", maxAttempts);
    }
}
