package br.com.readowl.readowl.privacy.application;

import br.com.readowl.readowl.shared.audit.SecurityEvents;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tudo o que a conta guarda sobre a pessoa, em JSON. Escreve aos poucos e consulta em blocos, sem transação: uma obra
 * pode ter milhares de capítulos, e um cliente lento não pode prender uma conexão com o banco.
 * <p>
 * Ficam de fora hashes e tokens, e o que outras pessoas escreveram, como a cópia do conteúdo nas denúncias e as notas da
 * moderação. Das revisões, vai só a publicada: o histórico inteiro pode chegar às cotas da conta.
 */
@Service
public class PersonalDataExport {

    private static final int BATCH_SIZE = 200;
    private static final UUID BEFORE_EVERY_ID = new UUID(0, 0);

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final SecurityEvents events;

    PersonalDataExport(JdbcTemplate jdbc, JsonMapper mapper, SecurityEvents events) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.events = events;
    }

    public void write(UUID userId, OutputStream out) {
        try (JsonGenerator json = mapper.createGenerator(out)) {
            json.writeStartObject();
            json.writeName("profile");
            json.writePOJO(rows("""
                    SELECT id, username, name, email, description, avatar_url, role, status, email_verified_at, email_new_chapters,
                           terms_version, terms_accepted_at, created_at, updated_at
                    FROM users WHERE id = ?
                    """, userId).getFirst());
            section(json, "externalLinks", "SELECT type, url FROM user_links WHERE user_id = ? ORDER BY position", userId);
            section(json, "linkedAccounts", "SELECT provider, created_at FROM accounts WHERE user_id = ?", userId);
            section(json, "knownDevices", "SELECT created_at, expires_at FROM known_devices WHERE user_id = ?", userId);
            section(json, "follows", "SELECT book_id, created_at FROM book_follows WHERE user_id = ?", userId);
            section(json, "ratings", "SELECT book_id, score, created_at, updated_at FROM book_ratings WHERE user_id = ?", userId);
            section(json, "reactions", "SELECT chapter_id, type, created_at, updated_at FROM chapter_reactions WHERE user_id = ?", userId);
            section(json, "commentLikes", "SELECT comment_id, created_at FROM comment_likes WHERE user_id = ?", userId);
            section(json, "readingProgress", "SELECT * FROM reading_progress WHERE user_id = ?", userId);
            batches(json, "comments", """
                    SELECT id, book_id, chapter_id, parent_id, content, edited_at, deleted_at, moderated_at, created_at
                    FROM comments WHERE user_id = ? AND id > ? ORDER BY id LIMIT ?
                    """, userId);
            batches(json, "notifications", """
                    SELECT id, type, book_id, chapter_id, read_at, created_at
                    FROM notifications WHERE user_id = ? AND id > ? ORDER BY id LIMIT ?
                    """, userId);
            batches(json, "reports", """
                    SELECT id, target_type, target_id, reason, details, status, resolved_at, created_at
                    FROM reports WHERE reporter_id = ? AND id > ? ORDER BY id LIMIT ?
                    """, userId);
            batches(json, "accessRecords", """
                    SELECT id, ip, action, created_at
                    FROM access_records WHERE user_id = ? AND id > ? ORDER BY id LIMIT ?
                    """, userId);
            books(json, userId);
            json.writeEndObject();
        }
        events.record(SecurityEvents.Event.DATA_EXPORTED, userId);
    }

    private void books(JsonGenerator json, UUID userId) {
        json.writeName("books");
        json.writeStartArray();
        List<Map<String, Object>> books = rows("""
                SELECT id, title, acronym, slug, synopsis, cover_url, release_frequency, status, publication_state, content_rating,
                       created_at, updated_at
                FROM books WHERE author_id = ? ORDER BY id
                """, userId);
        for (Map<String, Object> book : books) {
            UUID bookId = (UUID) book.get("id");
            json.writeStartObject();
            book.forEach((name, value) -> {
                json.writeName(name);
                json.writePOJO(value);
            });
            section(json, "volumes", "SELECT id, title, display_order FROM volumes WHERE book_id = ? ORDER BY display_order", bookId);
            chapters(json, bookId);
            json.writeEndObject();
            json.flush();
        }
        json.writeEndArray();
    }

    /**
     * Um capítulo por consulta: o texto de cada um pode ter 2 MiB.
     */
    private void chapters(JsonGenerator json, UUID bookId) {
        json.writeName("chapters");
        json.writeStartArray();
        UUID last = BEFORE_EVERY_ID;
        List<Map<String, Object>> batch;
        do {
            batch = rows("""
                    SELECT c.id, c.title, c.slug, c.state, c.display_order, c.volume_id, c.created_at,
                           d.document::text AS draft, r.document::text AS published
                    FROM chapters c
                    LEFT JOIN chapter_drafts d ON d.chapter_id = c.id
                    LEFT JOIN chapter_revisions r ON r.id = c.published_revision_id
                    WHERE c.book_id = ? AND c.id > ? ORDER BY c.id LIMIT 1
                    """, bookId, last);
            for (Map<String, Object> chapter : batch) {
                chapter.computeIfPresent("draft", (name, text) -> mapper.readTree((String) text));
                chapter.computeIfPresent("published", (name, text) -> mapper.readTree((String) text));
                json.writePOJO(chapter);
                last = (UUID) chapter.get("id");
            }
            json.flush();
        } while (!batch.isEmpty());
        json.writeEndArray();
    }

    private void section(JsonGenerator json, String name, String sql, UUID id) {
        json.writeName(name);
        json.writePOJO(rows(sql, id));
    }

    private void batches(JsonGenerator json, String name, String sql, UUID userId) {
        json.writeName(name);
        json.writeStartArray();
        UUID last = BEFORE_EVERY_ID;
        List<Map<String, Object>> batch;
        do {
            batch = rows(sql, userId, last, BATCH_SIZE);
            for (Map<String, Object> row : batch) {
                json.writePOJO(row);
                last = (UUID) row.get("id");
            }
            json.flush();
        } while (batch.size() == BATCH_SIZE);
        json.writeEndArray();
    }

    private List<Map<String, Object>> rows(String sql, Object... parameters) {
        return jdbc.queryForList(sql, parameters).stream().map(this::readable).toList();
    }

    private Map<String, Object> readable(Map<String, Object> row) {
        Map<String, Object> readable = new LinkedHashMap<>();
        row.forEach((name, value) -> readable.put(name, readable(value)));
        return readable;
    }

    private Object readable(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : value;
    }
}
