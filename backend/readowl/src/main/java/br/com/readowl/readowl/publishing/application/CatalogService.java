package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.book.application.PublicBooks;
import br.com.readowl.readowl.book.domain.BookStatus;
import br.com.readowl.readowl.book.domain.ContentRating;
import br.com.readowl.readowl.genre.application.TaxonomyItem;
import br.com.readowl.readowl.publishing.application.ReadingService.AuthorView;
import br.com.readowl.readowl.publishing.infrastructure.CatalogQueries;
import br.com.readowl.readowl.publishing.infrastructure.CatalogQueries.Filter;
import br.com.readowl.readowl.publishing.infrastructure.CatalogQueries.Ordering;
import br.com.readowl.readowl.publishing.infrastructure.CatalogQueries.Row;
import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.text.Texts;
import br.com.readowl.readowl.user.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_FILTER_VALUES = 10;
    private static final String RELEVANCE = "relevance";
    private static final Map<String, Ordering> ORDERINGS = Map.of(
            "title", Ordering.TITLE_ASC,
            "-title", Ordering.TITLE_DESC,
            "createdAt", Ordering.CREATED_ASC,
            "-createdAt", Ordering.CREATED_DESC,
            "lastChapterAt", Ordering.LAST_CHAPTER_ASC,
            "-lastChapterAt", Ordering.LAST_CHAPTER_DESC,
            "views", Ordering.VIEWS_ASC,
            "-views", Ordering.VIEWS_DESC,
            RELEVANCE, Ordering.RELEVANCE_DESC);

    private final CatalogQueries queries;

    CatalogService(CatalogQueries queries) {
        this.queries = queries;
    }

    public record CatalogSearch(String query, List<UUID> genreIds, List<String> tags, BookStatus status, String author,
                                String sort, int page, int size) {
    }

    public record CatalogEntry(UUID id, String title, String acronym, String slug, String synopsis, BookStatus status,
                               ContentRating contentRating,
                               long viewCount, AuthorView author, List<TaxonomyItem> genres, List<TaxonomyItem> tags,
                               int publishedChapters, Instant lastChapterPublishedAt, Instant createdAt) {
    }

    public PageResponse<CatalogEntry> search(CatalogSearch search) {
        PageRequests.requireValid(search.page(), search.size());
        String query = Texts.blankToNull(search.query());
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            throw ApiException.badRequest("QUERY_TOO_LONG", "A busca aceita até " + MAX_QUERY_LENGTH + " caracteres.");
        }
        if (search.genreIds().size() > MAX_FILTER_VALUES || search.tags().size() > MAX_FILTER_VALUES) {
            throw ApiException.badRequest("TOO_MANY_FILTERS", "Use até " + MAX_FILTER_VALUES + " gêneros e tags.");
        }
        Ordering ordering = ordering(search.sort(), query);
        String author = Texts.blankToNull(search.author());
        Filter filter = new Filter(query, search.genreIds(), search.tags(), search.status(),
                author == null ? null : User.normalizeUsername(author), PublicBooks.viewerIsLoggedIn());

        long total = queries.count(filter);
        List<Row> rows = queries.find(filter, ordering, search.size(), (long) search.page() * search.size());
        List<UUID> ids = rows.stream().map(Row::id).toList();
        Map<UUID, List<TaxonomyItem>> genres = queries.genresOf(ids);
        Map<UUID, List<TaxonomyItem>> tags = queries.tagsOf(ids);

        List<CatalogEntry> content = rows.stream().map(row -> new CatalogEntry(row.id(), row.title(), row.acronym(),
                row.slug(), row.synopsis(), row.status(), row.contentRating(), row.viewCount(),
                new AuthorView(row.authorUsername(), row.authorName()),
                genres.getOrDefault(row.id(), List.of()), tags.getOrDefault(row.id(), List.of()),
                row.publishedChapters(), row.lastChapterPublishedAt(), row.createdAt())).toList();
        return new PageResponse<>(content, search.page(), search.size(), total,
                (int) Math.ceil((double) total / search.size()));
    }

    /**
     * Sem ordenação informada, uma busca ordena por relevância e o catálogo, pelas atualizações recentes.
     */
    private static Ordering ordering(String sort, String query) {
        if (sort == null || sort.isBlank()) {
            return query != null ? Ordering.RELEVANCE_DESC : Ordering.LAST_CHAPTER_DESC;
        }
        Ordering ordering = ORDERINGS.get(sort);
        if (ordering == null || (ordering == Ordering.RELEVANCE_DESC && query == null)) {
            throw ApiException.badRequest("INVALID_SORT",
                    "Ordenação não permitida: use title, createdAt, lastChapterAt, views ou, em buscas, relevance.");
        }
        return ordering;
    }
}
