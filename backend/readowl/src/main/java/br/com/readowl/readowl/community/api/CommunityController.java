package br.com.readowl.readowl.community.api;

import br.com.readowl.readowl.community.application.ChapterViewService;
import br.com.readowl.readowl.community.application.CommentService;
import br.com.readowl.readowl.community.application.CommentService.CommentResponse;
import br.com.readowl.readowl.community.application.RatingService;
import br.com.readowl.readowl.community.application.ReactionService;
import br.com.readowl.readowl.community.domain.Comment;
import br.com.readowl.readowl.community.domain.ReactionType;
import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
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

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class CommunityController {

    private final CommentService comments;
    private final RatingService ratings;
    private final ReactionService reactions;
    private final ChapterViewService views;

    CommunityController(CommentService comments, RatingService ratings, ReactionService reactions,
                        ChapterViewService views) {
        this.comments = comments;
        this.ratings = ratings;
        this.reactions = reactions;
        this.views = views;
    }

    record CommentRequest(@NotBlank @Size(max = Comment.MAX_CONTENT_LENGTH) String content) {
    }

    record RatingRequest(@NotNull @Min(1) @Max(5) Integer score) {
    }

    record ReactionRequest(@NotNull ReactionType type) {
    }

    @GetMapping("/books/{bookId}/comments")
    PageResponse<CommentResponse> bookComments(@PathVariable UUID bookId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(defaultValue = "-createdAt") String sort) {
        return comments.listOnBook(bookId, commentPage(page, size, sort));
    }

    @PostMapping("/books/{bookId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    CommentResponse commentOnBook(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId,
                                  @Valid @RequestBody CommentRequest body, HttpServletRequest request) {
        return comments.commentOnBook(bookId, body.content(), principal, request.getRemoteAddr());
    }

    @GetMapping("/chapters/{chapterId}/comments")
    PageResponse<CommentResponse> chapterComments(@PathVariable UUID chapterId,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(defaultValue = "-createdAt") String sort) {
        return comments.listOnChapter(chapterId, commentPage(page, size, sort));
    }

    @PostMapping("/chapters/{chapterId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    CommentResponse commentOnChapter(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId,
                                     @Valid @RequestBody CommentRequest body, HttpServletRequest request) {
        return comments.commentOnChapter(chapterId, body.content(), principal, request.getRemoteAddr());
    }

    @PatchMapping("/comments/{commentId}")
    CommentResponse editComment(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID commentId,
                                @Valid @RequestBody CommentRequest body, HttpServletRequest request) {
        return comments.edit(commentId, body.content(), principal, request.getRemoteAddr());
    }

    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComment(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID commentId) {
        comments.delete(commentId, principal);
    }

    @GetMapping("/books/{bookId}/rating")
    RatingService.RatingSummary rating(@PathVariable UUID bookId) {
        return ratings.summary(bookId);
    }

    @GetMapping("/books/{bookId}/rating/me")
    RatingService.MyRating myRating(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId) {
        return ratings.mine(bookId, principal);
    }

    @PutMapping("/books/{bookId}/rating/me")
    RatingService.MyRating rate(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId,
                                @Valid @RequestBody RatingRequest body, HttpServletRequest request) {
        return ratings.rate(bookId, body.score(), principal, request.getRemoteAddr());
    }

    @DeleteMapping("/books/{bookId}/rating/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeRating(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID bookId) {
        ratings.remove(bookId, principal);
    }

    @GetMapping("/chapters/{chapterId}/reactions")
    ReactionService.ReactionSummary reactions(@PathVariable UUID chapterId) {
        return reactions.summary(chapterId);
    }

    @GetMapping("/chapters/{chapterId}/reactions/me")
    ReactionService.MyReaction myReaction(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId) {
        return reactions.mine(chapterId, principal);
    }

    @PutMapping("/chapters/{chapterId}/reactions/me")
    ReactionService.MyReaction react(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId,
                                     @Valid @RequestBody ReactionRequest body, HttpServletRequest request) {
        return reactions.react(chapterId, body.type(), principal, request.getRemoteAddr());
    }

    @DeleteMapping("/chapters/{chapterId}/reactions/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeReaction(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId) {
        reactions.remove(chapterId, principal);
    }

    @PostMapping("/chapters/{chapterId}/views")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void recordView(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID chapterId,
                    HttpServletRequest request) {
        views.record(chapterId, principal, request.getRemoteAddr());
    }

    private static Pageable commentPage(int page, int size, String sort) {
        return PageRequests.of(page, size, sort, Set.of("createdAt"));
    }
}
