package br.com.readowl.readowl.publishing.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Os endereços que um visitante pode abrir: obras e capítulos no ar, de autor ativo, sem conteúdo adulto.
 */
@Repository
public class SitemapQueries {

    private static final int URLS_PER_FILE = 10_000;
    private static final String PUBLIC_BOOKS = """
            FROM books b JOIN users u ON u.id = b.author_id
            WHERE b.publication_state = 'PUBLISHED' AND u.status = 'ACTIVE' AND b.content_rating = 'GENERAL'
            """;
    private static final String PUBLIC_CHAPTERS = """
            FROM chapters c JOIN books b ON b.id = c.book_id JOIN users u ON u.id = b.author_id
            WHERE c.state = 'PUBLISHED' AND b.publication_state = 'PUBLISHED' AND u.status = 'ACTIVE'
              AND b.content_rating = 'GENERAL'
            """;

    private final JdbcTemplate jdbc;

    SitemapQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Url(String path, Instant updatedAt) {
    }

    /**
     * Sempre ao menos um arquivo, para o índice ser válido com o catálogo vazio.
     */
    public int files() {
        Long total = jdbc.queryForObject("SELECT (SELECT count(*) " + PUBLIC_BOOKS + ") + (SELECT count(*) " + PUBLIC_CHAPTERS + ")", Long.class);
        return Math.max(1, (int) Math.ceil((total == null ? 0 : total) / (double) URLS_PER_FILE));
    }

    /**
     * @param file a partir de 1
     */
    public List<Url> page(int file) {
        return jdbc.query("""
                SELECT '/obras/' || b.slug AS path, b.updated_at """ + " " + PUBLIC_BOOKS + """
                UNION ALL
                SELECT '/obras/' || b.slug || '/capitulos/' || c.slug, c.updated_at """ + " " + PUBLIC_CHAPTERS + """
                ORDER BY path LIMIT ? OFFSET ?
                """, (rs, row) -> {
            Timestamp updatedAt = rs.getTimestamp("updated_at");
            return new Url(rs.getString("path"), updatedAt.toInstant());
        }, URLS_PER_FILE, (long) (file - 1) * URLS_PER_FILE);
    }
}
