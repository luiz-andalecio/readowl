package br.com.readowl.readowl.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Insere linhas por SQL puro, para testar as constraints do banco sem a validação da aplicação.
 */
public class SqlFixtures {

    private static final String DRAFT_DOCUMENT = "{\"type\":\"doc\",\"content\":[]}";

    private final JdbcTemplate jdbc;

    public SqlFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID user() {
        String unique = shortId();
        return user("u" + unique, unique + "@example.com");
    }

    public UUID user(String username, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, version, created_at, updated_at, name, username, email, role, status)
                VALUES (?, 0, now(), now(), 'Pessoa', ?, ?, 'USER', 'ACTIVE')
                """, id, username, email);
        return id;
    }

    public UUID book(UUID authorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO books (id, version, created_at, updated_at, title, acronym, slug, synopsis, status,
                                   publication_state, author_id)
                VALUES (?, 0, now(), now(), 'Obra', 'TST', ?, 'Sinopse', 'ONGOING', 'DRAFT', ?)
                """, id, "obra-" + shortId(), authorId);
        return id;
    }

    public UUID volume(UUID bookId, int order) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO volumes (id, version, created_at, updated_at, title, display_order, book_id)
                VALUES (?, 0, now(), now(), 'Volume', ?, ?)
                """, id, order, bookId);
        return id;
    }

    public UUID chapter(UUID bookId, UUID volumeId, int order) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chapters (id, version, created_at, updated_at, title, slug, state, display_order,
                                      book_id, volume_id)
                VALUES (?, 0, now(), now(), 'Capitulo', ?, 'DRAFT', ?, ?, ?)
                """, id, "capitulo-" + shortId(), order, bookId, volumeId);
        return id;
    }

    public UUID revision(UUID chapterId, UUID authorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chapter_revisions (id, chapter_id, revision_number, document, format_version,
                                               plain_text, html, created_by, created_at)
                VALUES (?, ?, 1, ?::jsonb, 1, '', '', ?, now())
                """, id, chapterId, DRAFT_DOCUMENT, authorId);
        return id;
    }

    public void publish(UUID chapterId, UUID revisionId) {
        jdbc.update("UPDATE chapters SET state = 'PUBLISHED', published_revision_id = ? WHERE id = ?",
                revisionId, chapterId);
    }

    public void draft(UUID chapterId) {
        jdbc.update("""
                INSERT INTO chapter_drafts (chapter_id, version, document, format_version, updated_at)
                VALUES (?, 0, ?::jsonb, 1, now())
                """, chapterId, DRAFT_DOCUMENT);
    }

    public UUID comment(UUID userId, UUID bookId, UUID chapterId, UUID parentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO comments (id, version, created_at, updated_at, content, user_id, book_id,
                                      chapter_id, parent_id)
                VALUES (?, 0, now(), now(), 'Comentario', ?, ?, ?, ?)
                """, id, userId, bookId, chapterId, parentId);
        return id;
    }

    public void rating(UUID userId, UUID bookId, int score) {
        jdbc.update("""
                INSERT INTO book_ratings (user_id, book_id, score, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                """, userId, bookId, score);
    }

    public void follow(UUID userId, UUID bookId) {
        jdbc.update("INSERT INTO book_follows (user_id, book_id, created_at) VALUES (?, ?, now())", userId, bookId);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
