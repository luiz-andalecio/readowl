package br.com.readowl.readowl.publishing.infrastructure;

import br.com.readowl.readowl.book.domain.BookStatus;
import br.com.readowl.readowl.book.domain.ContentRating;
import br.com.readowl.readowl.genre.application.TaxonomyItem;
import br.com.readowl.readowl.shared.text.Texts;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consulta do catálogo em SQL: filtros combináveis, busca textual e similaridade não têm equivalente em JPQL.
 * Os valores vão sempre como parâmetros, e as colunas de ordenação vêm de {@link Ordering}, nunca do cliente.
 */
@Repository
public class CatalogQueries {

    private static final String FROM = """
            FROM books b
            JOIN users u ON u.id = b.author_id
            WHERE b.publication_state = 'PUBLISHED' AND u.status = 'ACTIVE'
            """;
    private static final String QUERY_FILTER = """
             AND (b.search_vector @@ websearch_to_tsquery('portuguese', immutable_unaccent(:query))
                  OR b.search_title LIKE '%' || immutable_unaccent(lower(:likeQuery)) || '%' ESCAPE '\\'
                  OR immutable_unaccent(lower(:query)) <% b.search_title
                  OR b.acronym = upper(:query))
            """;
    private static final String RELEVANCE = """
            ts_rank(b.search_vector, websearch_to_tsquery('portuguese', immutable_unaccent(:query)))
                + word_similarity(immutable_unaccent(lower(:query)), b.search_title)
                + CASE WHEN b.acronym = upper(:query) THEN 1 ELSE 0 END
            """;

    private final NamedParameterJdbcTemplate jdbc;

    CatalogQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Ordering {
        RELEVANCE_DESC(null),
        TITLE_ASC("b.title ASC"),
        TITLE_DESC("b.title DESC"),
        CREATED_ASC("b.created_at ASC"),
        CREATED_DESC("b.created_at DESC"),
        LAST_CHAPTER_ASC("b.last_chapter_published_at ASC NULLS LAST"),
        LAST_CHAPTER_DESC("b.last_chapter_published_at DESC NULLS LAST"),
        VIEWS_ASC("b.view_count ASC"),
        VIEWS_DESC("b.view_count DESC");

        private final String sql;

        Ordering(String sql) {
            this.sql = sql;
        }
    }

    public record Filter(String query, List<UUID> genreIds, List<String> tags, BookStatus status, String author,
                         boolean includeAdult) {
    }

    public record Row(UUID id, String title, String acronym, String slug, String synopsis, BookStatus status,
                      ContentRating contentRating, long viewCount, Double averageRating, long ratingCount,
                      String authorUsername, String authorName, int publishedChapters,
                      Instant lastChapterPublishedAt, Instant createdAt) {
    }

    public record Result(List<Row> rows, long total) {
    }

    private long count(Filter filter) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        String sql = "SELECT count(*) " + FROM + conditions(filter, parameters);
        Long total = jdbc.queryForObject(sql, parameters, Long.class);
        return total == null ? 0 : total;
    }

    /**
     * Ordenar por relevância já percorre todas as candidatas, então o total sai da mesma consulta, em vez de repetir o
     * trabalho. As demais ordenações usam um índice e param no fim da página: para elas, contar à parte é mais barato.
     */
    public Result find(Filter filter, Ordering ordering, int limit, long offset) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("offset", offset);
        String order = ordering == Ordering.RELEVANCE_DESC ? "(" + RELEVANCE + ") DESC" : ordering.sql;
        // Em duas fases: acha só os IDs da página e depois lê o resto deles. Assim, ordenar não carrega a sinopse de cada
        // candidata, nem conta os capítulos das obras que OFFSET pula.
        boolean countedTogether = ordering == Ordering.RELEVANCE_DESC;
        String sql = """
                SELECT b.id, b.title, b.acronym, b.slug, b.synopsis, b.status, b.content_rating, b.view_count,
                       b.last_chapter_published_at, b.created_at, u.username, u.name, rating.average_rating, rating.rating_count,
                       (SELECT count(*) FROM chapters c WHERE c.book_id = b.id AND c.state = 'PUBLISHED') AS published_chapters
                """ + (countedTogether ? ", page.total" : "") + " FROM (SELECT b.id" + (countedTogether ? ", count(*) OVER () AS total " : " ") + FROM + conditions(filter, parameters)
                + " ORDER BY " + order + ", b.id LIMIT :limit OFFSET :offset) page"
                + " JOIN books b ON b.id = page.id JOIN users u ON u.id = b.author_id"
                + " CROSS JOIN LATERAL (SELECT round(avg(r.score), 2)::float8 AS average_rating, count(*) AS rating_count"
                + " FROM book_ratings r JOIN users rater ON rater.id = r.user_id"
                + " WHERE r.book_id = b.id AND rater.status = 'ACTIVE') rating"
                + " ORDER BY " + order + ", b.id";
        long[] total = {-1};
        List<Row> rows = jdbc.query(sql, parameters, (rs, rowNumber) -> {
            if (countedTogether) {
                total[0] = rs.getLong("total");
            }
            return row(rs, rowNumber);
        });
        // Sem linhas, como em uma página depois da última, a consulta não diz o total.
        return new Result(rows, total[0] >= 0 ? total[0] : count(filter));
    }

    public Map<UUID, List<TaxonomyItem>> genresOf(Collection<UUID> bookIds) {
        return items("""
                SELECT bg.book_id, g.id, g.name FROM book_genres bg JOIN genres g ON g.id = bg.genre_id
                WHERE bg.book_id IN (:bookIds) ORDER BY g.name
                """, bookIds);
    }

    public Map<UUID, List<TaxonomyItem>> tagsOf(Collection<UUID> bookIds) {
        return items("""
                SELECT bt.book_id, t.id, t.name FROM book_tags bt JOIN tags t ON t.id = bt.tag_id
                WHERE bt.book_id IN (:bookIds) ORDER BY t.name
                """, bookIds);
    }

    private Map<UUID, List<TaxonomyItem>> items(String sql, Collection<UUID> bookIds) {
        Map<UUID, List<TaxonomyItem>> byBook = new HashMap<>();
        if (bookIds.isEmpty()) {
            return byBook;
        }
        jdbc.query(sql, Map.of("bookIds", bookIds), (ResultSet rs) -> {
            byBook.computeIfAbsent(rs.getObject("book_id", UUID.class), id -> new ArrayList<>())
                    .add(new TaxonomyItem(rs.getObject("id", UUID.class), rs.getString("name")));
        });
        return byBook;
    }

    private static String conditions(Filter filter, MapSqlParameterSource parameters) {
        StringBuilder sql = new StringBuilder();
        if (filter.query() != null) {
            sql.append(QUERY_FILTER);
            parameters.addValue("query", filter.query());
            parameters.addValue("likeQuery", Texts.escapeLike(filter.query()));
        }
        if (!filter.includeAdult()) {
            sql.append(" AND b.content_rating = 'GENERAL'");
        }
        if (filter.status() != null) {
            sql.append(" AND b.status = :status");
            parameters.addValue("status", filter.status().name());
        }
        if (filter.author() != null) {
            sql.append(" AND u.username = :author");
            parameters.addValue("author", filter.author());
        }
        // Cada gênero e tag informado precisa estar presente: os filtros estreitam o resultado.
        for (int index = 0; index < filter.genreIds().size(); index++) {
            sql.append(" AND EXISTS (SELECT 1 FROM book_genres bg WHERE bg.book_id = b.id AND bg.genre_id = :genre")
                    .append(index).append(')');
            parameters.addValue("genre" + index, filter.genreIds().get(index));
        }
        for (int index = 0; index < filter.tags().size(); index++) {
            sql.append(" AND EXISTS (SELECT 1 FROM book_tags bt JOIN tags t ON t.id = bt.tag_id")
                    .append(" WHERE bt.book_id = b.id AND lower(t.name) = lower(:tag").append(index).append("))");
            parameters.addValue("tag" + index, filter.tags().get(index));
        }
        return sql.toString();
    }

    private static Row row(ResultSet rs, int rowNumber) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("acronym"),
                rs.getString("slug"), rs.getString("synopsis"), BookStatus.valueOf(rs.getString("status")),
                ContentRating.valueOf(rs.getString("content_rating")), rs.getLong("view_count"), rs.getObject("average_rating", Double.class),
                rs.getLong("rating_count"), rs.getString("username"), rs.getString("name"),
                rs.getInt("published_chapters"), instant(rs, "last_chapter_published_at"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
