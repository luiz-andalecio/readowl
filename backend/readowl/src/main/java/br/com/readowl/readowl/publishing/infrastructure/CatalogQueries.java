package br.com.readowl.readowl.publishing.infrastructure;

import br.com.readowl.readowl.book.domain.BookStatus;
import br.com.readowl.readowl.genre.application.TaxonomyItem;
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
                  OR immutable_unaccent(lower(b.title)) LIKE '%' || immutable_unaccent(lower(:likeQuery)) || '%' ESCAPE '\\'
                  OR immutable_unaccent(lower(:query)) <% immutable_unaccent(lower(b.title))
                  OR b.acronym = upper(:query))
            """;
    private static final String RELEVANCE = """
            ts_rank(b.search_vector, websearch_to_tsquery('portuguese', immutable_unaccent(:query)))
                + word_similarity(immutable_unaccent(lower(:query)), immutable_unaccent(lower(b.title)))
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

    public record Filter(String query, List<UUID> genreIds, List<String> tags, BookStatus status, String author) {
    }

    public record Row(UUID id, String title, String acronym, String slug, String synopsis, BookStatus status,
                      long viewCount, String authorUsername, String authorName, int publishedChapters,
                      Instant lastChapterPublishedAt, Instant createdAt) {
    }

    public long count(Filter filter) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        String sql = "SELECT count(*) " + FROM + conditions(filter, parameters);
        Long total = jdbc.queryForObject(sql, parameters, Long.class);
        return total == null ? 0 : total;
    }

    public List<Row> find(Filter filter, Ordering ordering, int limit, long offset) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("offset", offset);
        String order = ordering == Ordering.RELEVANCE_DESC ? "(" + RELEVANCE + ") DESC" : ordering.sql;
        String sql = """
                SELECT b.id, b.title, b.acronym, b.slug, b.synopsis, b.status, b.view_count,
                       b.last_chapter_published_at, b.created_at, u.username, u.name,
                       (SELECT count(*) FROM chapters c WHERE c.book_id = b.id AND c.state = 'PUBLISHED') AS published_chapters
                """ + FROM + conditions(filter, parameters)
                + " ORDER BY " + order + ", b.id LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, parameters, CatalogQueries::row);
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
            parameters.addValue("likeQuery", escapeLike(filter.query()));
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
                rs.getLong("view_count"), rs.getString("username"), rs.getString("name"),
                rs.getInt("published_chapters"), instant(rs, "last_chapter_published_at"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
