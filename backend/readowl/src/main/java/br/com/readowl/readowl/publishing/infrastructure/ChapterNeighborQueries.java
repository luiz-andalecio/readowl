package br.com.readowl.readowl.publishing.infrastructure;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;

/**
 * Em SQL porque usa funções de janela, que devolvem só a linha pedida em vez do índice inteiro da obra.
 */
@Repository
public class ChapterNeighborQueries {

    private final NamedParameterJdbcTemplate jdbc;

    ChapterNeighborQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Títulos e slugs nulos quando não há vizinho.
     */
    public record Neighbors(String previousTitle, String previousSlug, String nextTitle, String nextSlug) {
    }

    /**
     * Na ordem de leitura: primeiro os capítulos sem volume, depois cada volume em ordem.
     */
    public Neighbors find(UUID bookId, UUID chapterId) {
        return jdbc.queryForObject("""
                SELECT previous_title, previous_slug, next_title, next_slug FROM (
                    SELECT c.id,
                           lag(c.title) OVER reading AS previous_title, lag(c.slug) OVER reading AS previous_slug,
                           lead(c.title) OVER reading AS next_title, lead(c.slug) OVER reading AS next_slug
                    FROM chapters c LEFT JOIN volumes v ON v.id = c.volume_id
                    WHERE c.book_id = :bookId AND c.state = 'PUBLISHED'
                    WINDOW reading AS (ORDER BY v.display_order ASC NULLS FIRST, c.display_order ASC)
                ) ordered WHERE id = :chapterId
                """, Map.of("bookId", bookId, "chapterId", chapterId), (rs, row) -> new Neighbors(
                rs.getString("previous_title"), rs.getString("previous_slug"),
                rs.getString("next_title"), rs.getString("next_slug")));
    }
}
