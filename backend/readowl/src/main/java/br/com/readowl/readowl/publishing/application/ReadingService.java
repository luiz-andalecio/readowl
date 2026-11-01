package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.book.application.PublicBooks;
import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.book.domain.BookStatus;
import br.com.readowl.readowl.book.domain.ContentRating;
import br.com.readowl.readowl.genre.application.TaxonomyItem;
import br.com.readowl.readowl.publishing.domain.ChapterState;
import br.com.readowl.readowl.publishing.infrastructure.ChapterNeighborQueries;
import br.com.readowl.readowl.publishing.infrastructure.ChapterNeighborQueries.Neighbors;
import br.com.readowl.readowl.publishing.infrastructure.ChapterRepository;
import br.com.readowl.readowl.publishing.infrastructure.ChapterRepository.ChapterContentRow;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Conteúdo para visitantes. Só devolve o que está publicado: rascunhos e volumes sem capítulos publicados,
 * cujos títulos podem ainda ser privados, não aparecem. A contagem de visualizações por capítulo fica de
 * fora porque é restrita ao autor.
 */
@Service
@Transactional(readOnly = true)
public class ReadingService {

    private final PublicBooks publicBooks;
    private final ChapterRepository chapters;
    private final ChapterNeighborQueries neighborQueries;

    ReadingService(PublicBooks publicBooks, ChapterRepository chapters, ChapterNeighborQueries neighborQueries) {
        this.publicBooks = publicBooks;
        this.chapters = chapters;
        this.neighborQueries = neighborQueries;
    }

    public record AuthorView(String username, String name) {
    }

    public record PublicVolume(UUID id, String title) {
    }

    public record PublicChapter(UUID id, String title, String slug, UUID volumeId, Instant publishedAt) {
    }

    public record PublicBook(UUID id, String title, String acronym, String slug, String synopsis,
                             String releaseFrequency, BookStatus status, ContentRating contentRating, long viewCount, AuthorView author,
                             List<TaxonomyItem> genres, List<TaxonomyItem> tags, Instant lastChapterPublishedAt,
                             Instant createdAt, List<PublicVolume> volumes, long publishedChapters) {
    }

    public record ChapterLink(String title, String slug) {
    }

    public record BookLink(UUID id, String title, String slug, AuthorView author) {
    }

    public record ChapterReading(UUID id, String title, String slug, PublicVolume volume, String html,
                                 Instant publishedAt, BookLink book, ChapterLink previous, ChapterLink next) {
    }

    public PublicBook book(String slug) {
        Book book = publicBooks.requireBySlug(slug);
        return new PublicBook(book.getId(), book.getTitle(), book.getAcronym(), book.getSlug(), book.getSynopsis(),
                book.getReleaseFrequency(), book.getStatus(), book.getContentRating(), book.getViewCount(), author(book),
                book.getGenres().stream().map(TaxonomyItem::from).sorted(Comparator.comparing(TaxonomyItem::name)).toList(),
                book.getTags().stream().map(TaxonomyItem::from).sorted(Comparator.comparing(TaxonomyItem::name)).toList(),
                book.getLastChapterPublishedAt(), book.getCreatedAt(),
                chapters.findPublishedVolumes(book.getId()).stream()
                        .map(volume -> new PublicVolume(volume.id(), volume.title())).toList(),
                chapters.countByBookIdAndState(book.getId(), ChapterState.PUBLISHED));
    }

    /**
     * A lista vem em páginas: uma obra longa pode ter milhares de capítulos.
     */
    public PageResponse<PublicChapter> chapters(String slug, Pageable pageable) {
        Book book = publicBooks.requireBySlug(slug);
        return PageResponse.from(chapters.findPublishedRows(book.getId(), pageable)
                .map(row -> new PublicChapter(row.id(), row.title(), row.slug(), row.volumeId(), row.publishedAt())));
    }

    public ChapterReading chapter(String bookSlug, String chapterSlug) {
        Book book = publicBooks.requireBySlug(bookSlug);
        ChapterContentRow chapter = chapters.findPublishedContent(book.getId(), chapterSlug)
                .orElseThrow(() -> ApiException.notFound("CHAPTER_NOT_FOUND", "Capítulo não encontrado."));

        Neighbors neighbors = neighborQueries.find(book.getId(), chapter.id());
        return new ChapterReading(chapter.id(), chapter.title(), chapter.slug(),
                chapter.volumeId() == null ? null : new PublicVolume(chapter.volumeId(), chapter.volumeTitle()),
                chapter.html(), chapter.publishedAt(),
                new BookLink(book.getId(), book.getTitle(), book.getSlug(), author(book)),
                link(neighbors.previousTitle(), neighbors.previousSlug()),
                link(neighbors.nextTitle(), neighbors.nextSlug()));
    }

    private static ChapterLink link(String title, String slug) {
        return slug == null ? null : new ChapterLink(title, slug);
    }

    private static AuthorView author(Book book) {
        return new AuthorView(book.getAuthor().getUsername(), book.getAuthor().getName());
    }
}
