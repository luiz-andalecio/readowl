package br.com.readowl.readowl.privacy.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * As obras saem com a conta, em cascata, salvo as que têm denúncia aberta, que são prova. Essas ficam fora do ar, porque
 * o autor não está mais ativo, e saem quando a última denúncia for encerrada.
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

    RetainedBooks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void deleteUnreportedOf(UUID authorId) {
        jdbc.update("DELETE FROM books b WHERE b.author_id = ? AND" + WITHOUT_OPEN_REPORT, authorId);
    }

    int deleteReleased() {
        return jdbc.update("""
                DELETE FROM books b
                WHERE b.author_id IN (SELECT u.id FROM users u WHERE u.status = 'DELETED') AND""" + WITHOUT_OPEN_REPORT);
    }
}
