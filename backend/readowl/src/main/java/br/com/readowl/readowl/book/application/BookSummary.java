package br.com.readowl.readowl.book.application;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.book.domain.BookStatus;
import br.com.readowl.readowl.book.domain.ContentRating;
import br.com.readowl.readowl.book.domain.PublicationState;

import java.time.Instant;
import java.util.UUID;

public record BookSummary(UUID id, String title, String acronym, String slug, BookStatus status,
                          PublicationState publicationState, ContentRating contentRating, boolean moderated, String authorUsername,
                          Instant createdAt, Instant updatedAt) {

    static BookSummary from(Book book) {
        return new BookSummary(book.getId(), book.getTitle(), book.getAcronym(), book.getSlug(), book.getStatus(),
                book.getPublicationState(), book.getContentRating(), book.isModerated(), book.getAuthor().getUsername(), book.getCreatedAt(),
                book.getUpdatedAt());
    }
}
