package br.com.readowl.readowl.book.application;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.book.domain.BookStatus;
import br.com.readowl.readowl.book.infrastructure.BookRepository;
import br.com.readowl.readowl.genre.application.TaxonomyService;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class BookService {

    private final BookRepository books;
    private final BookAccess access;
    private final UserService users;
    private final TaxonomyService taxonomy;
    private final TransactionOperations transaction;

    BookService(BookRepository books, BookAccess access, UserService users, TaxonomyService taxonomy,
                TransactionOperations transaction) {
        this.books = books;
        this.access = access;
        this.users = users;
        this.taxonomy = taxonomy;
        this.transaction = transaction;
    }

    public record NewBook(String title, String acronym, String synopsis, String releaseFrequency,
                          List<UUID> genreIds, List<String> tagNames) {
    }

    /**
     * Campos nulos não são alterados.
     */
    public record BookChanges(String title, String acronym, String synopsis, String releaseFrequency,
                              BookStatus status, List<UUID> genreIds, List<String> tagNames) {
    }

    public BookDetails create(NewBook data, SessionUser principal) {
        String title = data.title().trim();
        String slug = Book.slugFor(data.acronym(), title);
        return translatingSlugConflict(new AtomicReference<>(slug), () -> transaction.execute(status -> {
            requireFreeSlug(slug);
            Book book = new Book(title, data.acronym(), slug, data.synopsis().trim(),
                    users.requireById(principal.id()));
            book.updateReleaseFrequency(blankToNull(data.releaseFrequency()));
            book.replaceGenres(taxonomy.requireGenres(data.genreIds()));
            book.replaceTags(taxonomy.resolveTags(data.tagNames() == null ? List.of() : data.tagNames()));
            return BookDetails.from(books.saveAndFlush(book));
        }));
    }

    public BookDetails update(UUID id, BookChanges changes, SessionUser principal) {
        AtomicReference<String> targetSlug = new AtomicReference<>();
        return translatingSlugConflict(targetSlug, () -> transaction.execute(status -> {
            Book book = access.requireManageable(id, principal);
            String title = changes.title() != null ? changes.title().trim() : book.getTitle();
            String acronym = changes.acronym() != null ? changes.acronym() : book.getAcronym();
            String slug = Book.slugFor(acronym, title);
            targetSlug.set(slug);
            if (!slug.equals(book.getSlug())) {
                requireFreeSlug(slug);
            }
            book.rename(title, acronym);
            if (changes.synopsis() != null) {
                book.updateSynopsis(changes.synopsis().trim());
            }
            if (changes.releaseFrequency() != null) {
                book.updateReleaseFrequency(blankToNull(changes.releaseFrequency()));
            }
            if (changes.status() != null) {
                book.updateStatus(changes.status());
            }
            if (changes.genreIds() != null) {
                book.replaceGenres(taxonomy.requireGenres(changes.genreIds()));
            }
            if (changes.tagNames() != null) {
                book.replaceTags(taxonomy.resolveTags(changes.tagNames()));
            }
            return BookDetails.from(books.saveAndFlush(book));
        }));
    }

    /**
     * A exclusão em cascata remove capítulos, comentários e avaliações, sem volta. Por isso exige que a obra
     * esteja fora do ar e que o título seja digitado.
     */
    @Transactional
    public void delete(UUID id, String confirmTitle, SessionUser principal) {
        Book book = access.requireManageable(id, principal);
        if (book.isPublished()) {
            throw ApiException.conflict("BOOK_PUBLISHED", "Despublique a obra antes de excluí-la.");
        }
        if (confirmTitle == null || !confirmTitle.trim().equals(book.getTitle())) {
            throw ApiException.badRequest("TITLE_CONFIRMATION_MISMATCH", "O título informado não confere.");
        }
        books.delete(book);
    }

    @Transactional(readOnly = true)
    public BookDetails details(UUID id, SessionUser principal) {
        return BookDetails.from(access.requireManageable(id, principal));
    }

    @Transactional(readOnly = true)
    public PageResponse<BookSummary> listOwn(SessionUser principal, Pageable pageable) {
        return PageResponse.from(books.findByAuthorId(principal.id(), pageable).map(BookSummary::from));
    }

    @Transactional(readOnly = true)
    public PageResponse<BookSummary> listAll(Pageable pageable) {
        return PageResponse.from(books.findAllBy(pageable).map(BookSummary::from));
    }

    private void requireFreeSlug(String slug) {
        if (books.existsBySlug(slug)) {
            throw slugTaken();
        }
    }

    /**
     * Duas operações simultâneas pelo mesmo slug: a perdedora recebe a resposta que teria se chegasse depois.
     * Consulta de novo porque a mensagem do banco varia com o idioma do servidor.
     */
    private BookDetails translatingSlugConflict(AtomicReference<String> slug, Supplier<BookDetails> operation) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException ex) {
            if (slug.get() != null && books.existsBySlug(slug.get())) {
                throw slugTaken();
            }
            throw ex;
        }
    }

    private static ApiException slugTaken() {
        return ApiException.conflict("BOOK_SLUG_TAKEN", "Já existe uma obra com esta sigla e este título. Escolha outra sigla.");
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
