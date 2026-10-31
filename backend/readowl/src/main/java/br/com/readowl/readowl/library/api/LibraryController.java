package br.com.readowl.readowl.library.api;

import br.com.readowl.readowl.library.application.LibraryService;
import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/library")
class LibraryController {

    private final LibraryService library;

    LibraryController(LibraryService library) {
        this.library = library;
    }

    record ProgressRequest(@NotNull UUID chapterId, @NotNull @Min(0) @Max(100) Integer percent) {
    }

    @GetMapping
    PageResponse<LibraryService.LibraryEntry> list(@AuthenticationPrincipal SessionUser principal,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return library.list(principal, PageRequests.of(page, size, "-createdAt", Set.of("createdAt")));
    }

    @GetMapping("/{bookId}")
    LibraryService.LibraryState state(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId) {
        return library.state(bookId, principal);
    }

    @PutMapping("/{bookId}")
    LibraryService.LibraryState follow(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId) {
        return library.follow(bookId, principal);
    }

    @DeleteMapping("/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unfollow(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId) {
        library.unfollow(bookId, principal);
    }

    @PutMapping("/{bookId}/progress")
    LibraryService.LibraryState recordProgress(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId,
                                               @Valid @RequestBody ProgressRequest body) {
        return library.recordProgress(bookId, body.chapterId(), body.percent(), principal);
    }
}
