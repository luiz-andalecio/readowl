package br.com.readowl.readowl.book.application;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.book.domain.BookStatus;
import br.com.readowl.readowl.book.domain.PublicationState;
import br.com.readowl.readowl.genre.application.TaxonomyItem;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record BookDetails(UUID id, String title, String acronym, String slug, String synopsis, String releaseFrequency,
                          BookStatus status, PublicationState publicationState, boolean moderated, long viewCount,
                          String authorUsername, long version,
                          List<TaxonomyItem> genres, List<TaxonomyItem> tags, Instant createdAt, Instant updatedAt) {

    /**
     * Chamado dentro de uma transação: lê os gêneros e as tags.
     */
    public static BookDetails from(Book book) {
        return new BookDetails(book.getId(), book.getTitle(), book.getAcronym(), book.getSlug(), book.getSynopsis(),
                book.getReleaseFrequency(), book.getStatus(), book.getPublicationState(), book.isModerated(),
                book.getViewCount(), book.getAuthor().getUsername(), book.getVersion(),
                book.getGenres().stream().map(TaxonomyItem::from)
                        .sorted(Comparator.comparing(TaxonomyItem::name)).toList(),
                book.getTags().stream().map(TaxonomyItem::from)
                        .sorted(Comparator.comparing(TaxonomyItem::name)).toList(),
                book.getCreatedAt(), book.getUpdatedAt());
    }
}
