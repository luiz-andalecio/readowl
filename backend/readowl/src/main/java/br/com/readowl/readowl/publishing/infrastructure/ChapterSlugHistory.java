package br.com.readowl.readowl.publishing.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Endereços antigos dos capítulos, dentro de cada obra. O slug atual de um capítulo sempre vence um antigo.
 */
@Repository
public class ChapterSlugHistory {

    private final JdbcTemplate jdbc;

    ChapterSlugHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void claim(UUID bookId, String slug) {
        jdbc.update("DELETE FROM chapter_slug_history WHERE book_id = ? AND old_slug = ?", bookId, slug);
    }

    public void recordRename(UUID bookId, UUID chapterId, String oldSlug, String newSlug) {
        if (oldSlug.equals(newSlug)) {
            return;
        }
        claim(bookId, newSlug);
        jdbc.update("""
                INSERT INTO chapter_slug_history (book_id, old_slug, chapter_id, created_at) VALUES (?, ?, ?, now())
                ON CONFLICT (book_id, old_slug) DO UPDATE SET chapter_id = EXCLUDED.chapter_id, created_at = now()
                """, bookId, oldSlug, chapterId);
    }

    /**
     * Só responde com um capítulo no ar: o endereço antigo de algo despublicado continua inexistente.
     */
    public List<String> findCurrentPublishedSlug(UUID bookId, String oldSlug) {
        return jdbc.queryForList("""
                SELECT c.slug FROM chapter_slug_history h JOIN chapters c ON c.id = h.chapter_id
                WHERE h.book_id = ? AND h.old_slug = ? AND c.state = 'PUBLISHED'
                """, String.class, bookId, oldSlug);
    }
}
