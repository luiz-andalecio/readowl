package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.book.application.PublicBooks;
import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.book.domain.BookStatus;
import br.com.readowl.readowl.genre.application.TaxonomyItem;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.publishing.domain.ChapterRevision;
import br.com.readowl.readowl.publishing.infrastructure.ChapterRepository;
import br.com.readowl.readowl.publishing.infrastructure.ChapterRepository.PublishedChapterRow;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    ReadingService(PublicBooks publicBooks, ChapterRepository chapters) {
        this.publicBooks = publicBooks;
        this.chapters = chapters;
    }

    public record AuthorView(String username, String name) {
    }

    public record PublicVolume(UUID id, String title) {
    }

    public record PublicChapter(UUID id, String title, String slug, UUID volumeId, Instant publishedAt) {
    }

    public record PublicBook(UUID id, String title, String acronym, String slug, String synopsis,
                             String releaseFrequency, BookStatus status, long viewCount, AuthorView author,
                             List<TaxonomyItem> genres, List<TaxonomyItem> tags, Instant lastChapterPublishedAt,
                             Instant createdAt, List<PublicVolume> volumes, List<PublicChapter> chapters) {
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
        List<PublishedChapterRow> rows = chapters.findPublishedRows(book.getId());

        Map<UUID, PublicVolume> volumes = new LinkedHashMap<>();
        List<PublicChapter> items = new ArrayList<>(rows.size());
        for (PublishedChapterRow row : rows) {
            if (row.volumeId() != null) {
                volumes.putIfAbsent(row.volumeId(), new PublicVolume(row.volumeId(), row.volumeTitle()));
            }
            items.add(new PublicChapter(row.id(), row.title(), row.slug(), row.volumeId(), row.publishedAt()));
        }
        return new PublicBook(book.getId(), book.getTitle(), book.getAcronym(), book.getSlug(), book.getSynopsis(),
                book.getReleaseFrequency(), book.getStatus(), book.getViewCount(), author(book),
                book.getGenres().stream().map(TaxonomyItem::from).sorted(Comparator.comparing(TaxonomyItem::name)).toList(),
                book.getTags().stream().map(TaxonomyItem::from).sorted(Comparator.comparing(TaxonomyItem::name)).toList(),
                book.getLastChapterPublishedAt(), book.getCreatedAt(), List.copyOf(volumes.values()), items);
    }

    public ChapterReading chapter(String bookSlug, String chapterSlug) {
        Book book = publicBooks.requireBySlug(bookSlug);
        Chapter chapter = chapters.findWithRevisionByBookIdAndSlug(book.getId(), chapterSlug)
                .filter(Chapter::isPublished)
                .orElseThrow(() -> ApiException.notFound("CHAPTER_NOT_FOUND", "Capítulo não encontrado."));
        ChapterRevision revision = chapter.getPublishedRevision();

        List<PublishedChapterRow> rows = chapters.findPublishedRows(book.getId());
        int position = indexOf(rows, chapter.getId());
        return new ChapterReading(chapter.getId(), chapter.getTitle(), chapter.getSlug(),
                chapter.getVolume() == null ? null : new PublicVolume(chapter.getVolume().getId(), chapter.getVolume().getTitle()),
                revision.getHtml(), revision.getCreatedAt(),
                new BookLink(book.getId(), book.getTitle(), book.getSlug(), author(book)),
                position > 0 ? link(rows.get(position - 1)) : null,
                position < rows.size() - 1 ? link(rows.get(position + 1)) : null);
    }

    private static int indexOf(List<PublishedChapterRow> rows, UUID chapterId) {
        for (int position = 0; position < rows.size(); position++) {
            if (rows.get(position).id().equals(chapterId)) {
                return position;
            }
        }
        throw new IllegalStateException("Capítulo publicado ausente do índice da obra");
    }

    private static ChapterLink link(PublishedChapterRow row) {
        return new ChapterLink(row.title(), row.slug());
    }

    private static AuthorView author(Book book) {
        return new AuthorView(book.getAuthor().getUsername(), book.getAuthor().getName());
    }
}
