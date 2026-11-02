package br.com.readowl.readowl.book.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Endereços antigos das obras. O slug atual de uma obra sempre vence um antigo: quem passa a usá-lo remove a linha.
 */
@Repository
public class BookSlugHistory {

    private final JdbcTemplate jdbc;

    BookSlugHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void claim(String slug) {
        jdbc.update("DELETE FROM book_slug_history WHERE old_slug = ?", slug);
    }

    /**
     * Voltar ao nome anterior funciona: o slug atual sai do histórico, e o que estava em uso entra.
     */
    public void recordRename(UUID bookId, String oldSlug, String newSlug) {
        if (oldSlug.equals(newSlug)) {
            return;
        }
        claim(newSlug);
        jdbc.update("""
                INSERT INTO book_slug_history (old_slug, book_id, created_at) VALUES (?, ?, now())
                ON CONFLICT (old_slug) DO UPDATE SET book_id = EXCLUDED.book_id, created_at = now()
                """, oldSlug, bookId);
    }

    public List<UUID> findBookId(String oldSlug) {
        return jdbc.queryForList("SELECT book_id FROM book_slug_history WHERE old_slug = ?", UUID.class, oldSlug);
    }
}
