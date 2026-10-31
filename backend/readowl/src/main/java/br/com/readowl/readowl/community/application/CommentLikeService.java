package br.com.readowl.readowl.community.application;

import br.com.readowl.readowl.community.domain.Comment;
import br.com.readowl.readowl.community.infrastructure.CommentLikeRepository;
import br.com.readowl.readowl.community.infrastructure.CommentRepository;
import br.com.readowl.readowl.community.infrastructure.CommentRepository.CommentCount;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.UUID;

@Service
public class CommentLikeService {

    private static final int MAX_LOOKUP_IDS = 50;

    private final CommentLikeRepository likes;
    private final CommentRepository comments;
    private final RateLimiter rateLimiter;
    private final TransactionOperations transaction;

    CommentLikeService(CommentLikeRepository likes, CommentRepository comments, RateLimiter rateLimiter,
                       TransactionOperations transaction) {
        this.likes = likes;
        this.comments = comments;
        this.rateLimiter = rateLimiter;
        this.transaction = transaction;
    }

    public record LikeState(boolean liked, long likeCount) {
    }

    public record LikedComments(List<UUID> commentIds) {
    }

    /**
     * Como nas avaliações, ninguém curte o próprio comentário.
     */
    public LikeState like(UUID commentId, SessionUser principal, String clientIp) {
        rateLimiter.acquire(RateLimitPolicy.INTERACTION_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.INTERACTION_ACCOUNT, principal.getName());
        return transaction.execute(status -> {
            Comment comment = comments.findNotDeleted(commentId).filter(CommentService::isVisible)
                    .orElseThrow(CommentService::notFound);
            if (comment.getUser().getId().equals(principal.id())) {
                throw ApiException.forbidden("OWN_COMMENT_LIKE", "Você não pode curtir o próprio comentário.");
            }
            likes.like(principal.id(), commentId);
            long count = likes.countByComment(List.of(commentId)).stream().mapToLong(CommentCount::total).sum();
            return new LikeState(true, count);
        });
    }

    /**
     * Funciona mesmo com o comentário fora do ar: a curtida é do usuário.
     */
    @Transactional
    public void unlike(UUID commentId, SessionUser principal) {
        likes.unlike(principal.id(), commentId);
    }

    /**
     * Fica fora das listas públicas de comentários, que não têm dados pessoais.
     */
    @Transactional(readOnly = true)
    public LikedComments likedAmong(List<UUID> commentIds, SessionUser principal) {
        if (commentIds.size() > MAX_LOOKUP_IDS) {
            throw ApiException.badRequest("TOO_MANY_IDS", "Consulte até " + MAX_LOOKUP_IDS + " comentários por vez.");
        }
        return new LikedComments(commentIds.isEmpty() ? List.of() : likes.findLikedAmong(principal.id(), commentIds));
    }
}
