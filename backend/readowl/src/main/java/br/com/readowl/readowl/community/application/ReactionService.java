package br.com.readowl.readowl.community.application;

import br.com.readowl.readowl.community.domain.ChapterReaction;
import br.com.readowl.readowl.community.domain.ChapterReactionId;
import br.com.readowl.readowl.community.domain.ReactionType;
import br.com.readowl.readowl.community.infrastructure.ChapterReactionRepository;
import br.com.readowl.readowl.community.infrastructure.ChapterReactionRepository.ReactionCount;
import br.com.readowl.readowl.publishing.application.PublicChapters;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ReactionService {

    private final ChapterReactionRepository reactions;
    private final PublicChapters publicChapters;
    private final RateLimiter rateLimiter;
    private final TransactionOperations transaction;

    ReactionService(ChapterReactionRepository reactions, PublicChapters publicChapters, RateLimiter rateLimiter,
                    TransactionOperations transaction) {
        this.reactions = reactions;
        this.publicChapters = publicChapters;
        this.rateLimiter = rateLimiter;
        this.transaction = transaction;
    }

    /**
     * @param counts todos os tipos, mesmo os sem reações, para o cliente não tratar campos ausentes
     */
    public record ReactionSummary(Map<ReactionType, Long> counts, long total) {
    }

    public record MyReaction(ReactionType type, ReactionSummary summary) {
    }

    @Transactional(readOnly = true)
    public ReactionSummary summary(UUID chapterId) {
        publicChapters.requirePublic(chapterId);
        return summarize(chapterId);
    }

    @Transactional(readOnly = true)
    public MyReaction mine(UUID chapterId, SessionUser principal) {
        publicChapters.requirePublic(chapterId);
        return currentState(chapterId, principal);
    }

    /**
     * Como nas avaliações, o autor não reage ao próprio capítulo.
     */
    public MyReaction react(UUID chapterId, ReactionType type, SessionUser principal, String clientIp) {
        rateLimiter.acquire(RateLimitPolicy.INTERACTION_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.INTERACTION_ACCOUNT, principal.getName());
        return transaction.execute(status -> {
            Chapter chapter = publicChapters.requirePublic(chapterId);
            if (chapter.getBook().getAuthor().getId().equals(principal.id())) {
                throw ApiException.forbidden("OWN_CHAPTER_REACTION", "O autor não pode reagir ao próprio capítulo.");
            }
            reactions.record(principal.id(), chapterId, type.name());
            return currentState(chapterId, principal);
        });
    }

    /**
     * Funciona mesmo com o capítulo fora do ar: a reação é do usuário.
     */
    @Transactional
    public void remove(UUID chapterId, SessionUser principal) {
        reactions.remove(principal.id(), chapterId);
    }

    private MyReaction currentState(UUID chapterId, SessionUser principal) {
        ReactionType type = reactions.findById(new ChapterReactionId(principal.id(), chapterId))
                .map(ChapterReaction::getType).orElse(null);
        return new MyReaction(type, summarize(chapterId));
    }

    private ReactionSummary summarize(UUID chapterId) {
        Map<ReactionType, Long> counts = new EnumMap<>(ReactionType.class);
        for (ReactionType type : ReactionType.values()) {
            counts.put(type, 0L);
        }
        for (ReactionCount count : reactions.countByType(chapterId)) {
            counts.put(count.type(), count.total());
        }
        return new ReactionSummary(counts, counts.values().stream().mapToLong(Long::longValue).sum());
    }
}
