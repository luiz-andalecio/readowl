package br.com.readowl.readowl.privacy.application;

import br.com.readowl.readowl.media.application.StoredImages;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * As obras saem com a conta, em cascata, salvo as que têm denúncia aberta, que são prova. Essas ficam fora do ar, porque
 * o autor não está mais ativo, e saem quando a última denúncia for encerrada. A capa e o banner saem do armazenamento depois do
 * commit.
 */
@Component
class RetainedBooks {

    private static final String WITHOUT_OPEN_REPORT = """
             NOT EXISTS (
                SELECT 1 FROM reports r
                WHERE r.status = 'OPEN'
                  AND ((r.target_type = 'BOOK' AND r.target_id = b.id)
                       OR (r.target_type = 'CHAPTER' AND r.target_id IN (SELECT c.id FROM chapters c WHERE c.book_id = b.id))))
            """;

    private final JdbcTemplate jdbc;
    private final StoredImages images;

    RetainedBooks(JdbcTemplate jdbc, StoredImages images) {
        this.jdbc = jdbc;
        this.images = images;
    }

    void deleteUnreportedOf(UUID authorId) {
        delete("DELETE FROM books b WHERE b.author_id = ? AND" + WITHOUT_OPEN_REPORT, authorId);
    }

    int deleteReleased() {
        return delete("""
                DELETE FROM books b
                WHERE b.author_id IN (SELECT u.id FROM users u WHERE u.status = 'DELETED') AND""" + WITHOUT_OPEN_REPORT);
    }

    private int delete(String sql, Object... parameters) {
        List<Map<String, Object>> deleted = jdbc.queryForList(sql + " RETURNING b.cover_key, b.banner_key", parameters);
        deleted.forEach(book -> {
            images.release((String) book.get("cover_key"));
            images.release((String) book.get("banner_key"));
        });
        return deleted.size();
    }
}
