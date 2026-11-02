package br.com.readowl.readowl.book.api;

import br.com.readowl.readowl.book.application.BookDetails;
import br.com.readowl.readowl.book.application.BookService;
import br.com.readowl.readowl.book.application.BookSummary;
import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimited;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class BookController {

    private static final Set<String> SORTS = Set.of("title", "createdAt", "updatedAt");

    private final BookService books;

    BookController(BookService books) {
        this.books = books;
    }

    @PostMapping("/books")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(RateLimitPolicy.BOOK_CREATION_ACCOUNT)
    BookDetails create(@AuthenticationPrincipal SessionUser principal, @Valid @RequestBody CreateBookRequest body) {
        return books.create(new BookService.NewBook(body.title(), body.acronym(), body.synopsis(),
                body.releaseFrequency(), body.contentRating(), body.coverUrl(), body.genreIds(), body.tags()), principal);
    }

    @GetMapping("/books/{id}/details")
    BookDetails details(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id) {
        return books.details(id, principal);
    }

    @PatchMapping("/books/{id}")
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    BookDetails update(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
                       @Valid @RequestBody UpdateBookRequest body) {
        return books.update(id, new BookService.BookChanges(body.title(), body.acronym(), body.synopsis(),
                body.releaseFrequency(), body.status(), body.contentRating(), body.coverUrl(), body.genreIds(), body.tags(), body.version()), principal);
    }

    @DeleteMapping("/books/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    void delete(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
                @RequestParam(required = false) String confirmTitle,
                @RequestBody(required = false) DeleteBookRequest body) {
        books.delete(id, body != null ? body.confirmTitle() : confirmTitle, principal);
    }

    /**
     * No corpo, o título de uma obra privada não vai parar em logs de acesso. O parâmetro na URL continua aceito.
     */
    record DeleteBookRequest(String confirmTitle) {
    }

    @GetMapping("/users/me/books")
    PageResponse<BookSummary> mine(@AuthenticationPrincipal SessionUser principal,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size,
                                   @RequestParam(defaultValue = "-updatedAt") String sort) {
        return books.listOwn(principal, PageRequests.of(page, size, sort, SORTS));
    }

    @GetMapping("/admin/books")
    PageResponse<BookSummary> all(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(defaultValue = "-updatedAt") String sort) {
        return books.listAll(PageRequests.of(page, size, sort, SORTS));
    }
}
