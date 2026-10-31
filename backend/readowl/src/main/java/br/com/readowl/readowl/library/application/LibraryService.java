package br.com.readowl.readowl.library.application;

import br.com.readowl.readowl.book.application.PublicBooks;
import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.book.domain.BookStatus;
import br.com.readowl.readowl.library.domain.BookFollow;
import br.com.readowl.readowl.library.domain.BookFollowId;
import br.com.readowl.readowl.library.domain.ReadingProgress;
import br.com.readowl.readowl.library.infrastructure.BookFollowRepository;
import br.com.readowl.readowl.library.infrastructure.ReadingProgressRepository;
import br.com.readowl.readowl.publishing.application.PublicChapters;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Biblioteca pessoal. O usuário vem sempre da sessão: não há como consultar ou alterar a de outra pessoa.
 */
@Service
@Transactional
public class LibraryService {

    private final BookFollowRepository follows;
    private final ReadingProgressRepository progress;
    private final PublicBooks publicBooks;
    private final PublicChapters publicChapters;

    LibraryService(BookFollowRepository follows, ReadingProgressRepository progress, PublicBooks publicBooks,
                   PublicChapters publicChapters) {
        this.follows = follows;
        this.progress = progress;
        this.publicBooks = publicBooks;
        this.publicChapters = publicChapters;
    }

    public record ProgressView(UUID chapterId, String chapterTitle, String chapterSlug, int percent, Instant updatedAt) {
    }

    public record LibraryState(boolean following, ProgressView progress) {
    }

    public record LibraryBook(UUID id, String title, String acronym, String slug, BookStatus status,
                              String authorUsername, String authorName, Instant lastChapterPublishedAt) {
    }

    public record LibraryEntry(LibraryBook book, Instant followedAt, ProgressView progress) {
    }

    @Transactional(readOnly = true)
    public PageResponse<LibraryEntry> list(SessionUser principal, Pageable pageable) {
        Page<BookFollow> page = follows.findPublicByUser(principal.id(), pageable);
        List<UUID> bookIds = page.getContent().stream().map(follow -> follow.getId().getBookId()).toList();
        Map<UUID, ReadingProgress> byBook = bookIds.isEmpty() ? Map.of()
                : progress.findByUserAndBooks(principal.id(), bookIds).stream()
                .collect(Collectors.toMap(item -> item.getId().getBookId(), Function.identity()));
        return PageResponse.from(page.map(follow -> new LibraryEntry(libraryBook(follow.getBook()),
                follow.getCreatedAt(), progressView(byBook.get(follow.getId().getBookId())))));
    }

    @Transactional(readOnly = true)
    public LibraryState state(UUID bookId, SessionUser principal) {
        publicBooks.requireById(bookId);
        return currentState(bookId, principal);
    }

    public LibraryState follow(UUID bookId, SessionUser principal) {
        publicBooks.requireById(bookId);
        follows.follow(principal.id(), bookId);
        return currentState(bookId, principal);
    }

    /**
     * Funciona mesmo com a obra fora do ar, e o progresso é mantido para quando o usuário voltar.
     */
    public void unfollow(UUID bookId, SessionUser principal) {
        follows.deleteById(new BookFollowId(principal.id(), bookId));
    }

    public LibraryState recordProgress(UUID bookId, UUID chapterId, int percent, SessionUser principal) {
        publicBooks.requireById(bookId);
        publicChapters.requirePublished(bookId, chapterId);
        progress.record(principal.id(), bookId, chapterId, percent);
        return currentState(bookId, principal);
    }

    private LibraryState currentState(UUID bookId, SessionUser principal) {
        return new LibraryState(follows.existsById(new BookFollowId(principal.id(), bookId)),
                progressView(progress.findByUserAndBooks(principal.id(), List.of(bookId)).stream().findFirst().orElse(null)));
    }

    /**
     * Um capítulo que saiu do ar não é oferecido como ponto de retomada.
     */
    private static ProgressView progressView(ReadingProgress item) {
        if (item == null || !item.getChapter().isPublished()) {
            return null;
        }
        Chapter chapter = item.getChapter();
        return new ProgressView(chapter.getId(), chapter.getTitle(), chapter.getSlug(), item.getPercent(), item.getUpdatedAt());
    }

    private static LibraryBook libraryBook(Book book) {
        return new LibraryBook(book.getId(), book.getTitle(), book.getAcronym(), book.getSlug(), book.getStatus(),
                book.getAuthor().getUsername(), book.getAuthor().getName(), book.getLastChapterPublishedAt());
    }
}
