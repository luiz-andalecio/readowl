package br.com.readowl.readowl.book.api;

import br.com.readowl.readowl.book.application.BookDetails;
import br.com.readowl.readowl.book.application.BookImageService;
import br.com.readowl.readowl.book.application.BookService;
import br.com.readowl.readowl.book.application.BookSummary;
import br.com.readowl.readowl.media.api.ImageUploads;
import br.com.readowl.readowl.media.application.StoredImageType;
import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimited;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class BookController {

    private static final Set<String> SORTS = Set.of("title", "createdAt", "updatedAt");

    private final BookService books;
    private final BookImageService images;

    BookController(BookService books, BookImageService images) {
        this.books = books;
        this.images = images;
    }

    record ImageLinkRequest(@NotBlank @Size(max = 1000) String url) {
    }

    @PostMapping("/books")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(RateLimitPolicy.BOOK_CREATION_ACCOUNT)
    BookDetails create(@AuthenticationPrincipal SessionUser principal, @Valid @RequestBody CreateBookRequest body) {
        return books.create(new BookService.NewBook(body.title(), body.acronym(), body.synopsis(),
                body.releaseFrequency(), body.contentRating(), body.genreIds(), body.tags()), principal);
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
                body.releaseFrequency(), body.status(), body.contentRating(), body.genreIds(), body.tags(), body.version()), principal);
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

    @PutMapping(value = "/books/{id}/cover", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    @RateLimited(RateLimitPolicy.IMAGE_ACCOUNT)
    BookDetails uploadCover(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id, HttpServletRequest request)
            throws IOException {
        return images.replaceWithFile(id, StoredImageType.COVER, ImageUploads.read(request), principal);
    }

    @PutMapping(value = "/books/{id}/cover", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimitPolicy.IMAGE_ACCOUNT)
    BookDetails linkCover(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
                          @Valid @RequestBody ImageLinkRequest body) {
        return images.replaceWithLink(id, StoredImageType.COVER, body.url(), principal);
    }

    @DeleteMapping("/books/{id}/cover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    void removeCover(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id) {
        images.remove(id, StoredImageType.COVER, principal);
    }

    @PutMapping(value = "/books/{id}/banner", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    @RateLimited(RateLimitPolicy.IMAGE_ACCOUNT)
    BookDetails uploadBanner(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id, HttpServletRequest request)
            throws IOException {
        return images.replaceWithFile(id, StoredImageType.BOOK_BANNER, ImageUploads.read(request), principal);
    }

    @PutMapping(value = "/books/{id}/banner", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimitPolicy.IMAGE_ACCOUNT)
    BookDetails linkBanner(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
                           @Valid @RequestBody ImageLinkRequest body) {
        return images.replaceWithLink(id, StoredImageType.BOOK_BANNER, body.url(), principal);
    }

    @DeleteMapping("/books/{id}/banner")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    void removeBanner(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id) {
        images.remove(id, StoredImageType.BOOK_BANNER, principal);
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
