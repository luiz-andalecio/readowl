package br.com.readowl.readowl.publishing.api;

import br.com.readowl.readowl.book.application.BookDetails;
import br.com.readowl.readowl.publishing.application.BookStructureService;
import br.com.readowl.readowl.publishing.application.ChapterDraftService;
import br.com.readowl.readowl.publishing.application.PublicationService;
import br.com.readowl.readowl.publishing.application.PublishingViews.BookContents;
import br.com.readowl.readowl.publishing.application.PublishingViews.ChapterView;
import br.com.readowl.readowl.publishing.application.PublishingViews.DraftView;
import br.com.readowl.readowl.publishing.application.PublishingViews.RevisionView;
import br.com.readowl.readowl.publishing.application.PublishingViews.VolumeView;
import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimited;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class PublishingController {

    private final BookStructureService structure;
    private final ChapterDraftService drafts;
    private final PublicationService publication;

    PublishingController(BookStructureService structure, ChapterDraftService drafts, PublicationService publication) {
        this.structure = structure;
        this.drafts = drafts;
        this.publication = publication;
    }

    /**
     * Com {@code version}, a versão lida, a alteração é recusada se outra pessoa tiver mudado o item depois disso.
     */
    record TitleRequest(@NotBlank @Size(max = 200) String title, Long version) {
    }

    record NewChapterRequest(@NotBlank @Size(max = 200) String title, UUID volumeId) {
    }

    record VolumeOrderRequest(@NotEmpty List<@NotNull UUID> volumeIds) {
    }

    record ChapterOrderRequest(UUID volumeId, @NotEmpty List<@NotNull UUID> chapterIds) {
    }

    record SaveDraftRequest(@NotNull Long version, @NotNull JsonNode document) {
    }

    record PublishChapterRequest(@NotNull Long draftVersion) {
    }

    @GetMapping("/books/{bookId}/contents")
    BookContents contents(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId) {
        return structure.contents(bookId, principal);
    }

    @PostMapping("/books/{bookId}/publish")
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    BookDetails publishBook(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId) {
        return publication.publishBook(bookId, principal);
    }

    @PostMapping("/books/{bookId}/unpublish")
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    BookDetails unpublishBook(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId) {
        return publication.unpublishBook(bookId, principal);
    }

    @PostMapping("/books/{bookId}/volumes")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    VolumeView createVolume(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId,
                            @Valid @RequestBody TitleRequest body) {
        return structure.createVolume(bookId, body.title(), principal);
    }

    @PutMapping("/books/{bookId}/volumes/order")
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    List<VolumeView> reorderVolumes(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId,
                                    @Valid @RequestBody VolumeOrderRequest body) {
        return structure.reorderVolumes(bookId, body.volumeIds(), principal);
    }

    @PatchMapping("/volumes/{volumeId}")
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    VolumeView renameVolume(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID volumeId,
                            @Valid @RequestBody TitleRequest body) {
        return structure.renameVolume(volumeId, body.title(), body.version(), principal);
    }

    @DeleteMapping("/volumes/{volumeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    void deleteVolume(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID volumeId) {
        structure.deleteVolume(volumeId, principal);
    }

    @PostMapping("/books/{bookId}/chapters")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    ChapterView createChapter(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId,
                              @Valid @RequestBody NewChapterRequest body) {
        return structure.createChapter(bookId, body.title(), body.volumeId(), principal);
    }

    @PutMapping("/books/{bookId}/chapters/order")
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    List<ChapterView> reorderChapters(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId,
                                      @Valid @RequestBody ChapterOrderRequest body) {
        return structure.reorderChapters(bookId, body.volumeId(), body.chapterIds(), principal);
    }

    @PatchMapping("/chapters/{chapterId}")
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    ChapterView renameChapter(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId,
                              @Valid @RequestBody TitleRequest body) {
        return structure.renameChapter(chapterId, body.title(), body.version(), principal);
    }

    @DeleteMapping("/chapters/{chapterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    void deleteChapter(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId) {
        structure.deleteChapter(chapterId, principal);
    }

    @GetMapping("/chapters/{chapterId}/draft")
    DraftView draft(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId) {
        return drafts.get(chapterId, principal);
    }

    @PutMapping("/chapters/{chapterId}/draft")
    @RateLimited(RateLimitPolicy.DRAFT_SAVE_ACCOUNT)
    DraftView saveDraft(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId,
                        @Valid @RequestBody SaveDraftRequest body) {
        return drafts.save(chapterId, body.version(), body.document(), principal);
    }

    @PostMapping("/chapters/{chapterId}/publish")
    @RateLimited(RateLimitPolicy.PUBLICATION_ACCOUNT)
    ChapterView publishChapter(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId,
                               @Valid @RequestBody PublishChapterRequest body) {
        return publication.publishChapter(chapterId, body.draftVersion(), principal);
    }

    @PostMapping("/chapters/{chapterId}/unpublish")
    @RateLimited(RateLimitPolicy.AUTHORING_ACCOUNT)
    ChapterView unpublishChapter(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId) {
        return publication.unpublishChapter(chapterId, principal);
    }

    @GetMapping("/chapters/{chapterId}/revisions")
    PageResponse<RevisionView> revisions(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return publication.listRevisions(chapterId,
                PageRequests.of(page, size, "-revisionNumber", Set.of("revisionNumber")), principal);
    }
}
