package br.com.readowl.readowl.community.application;

import br.com.readowl.readowl.community.infrastructure.ChapterViewRepository;
import br.com.readowl.readowl.publishing.application.PublicChapters;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.UUID;

/**
 * A visita indica que o capítulo foi aberto, não que foi lido até o fim. O cliente só informa o capítulo:
 * quem decide se conta é o servidor, e a resposta é a mesma nos dois casos.
 */
@Service
public class ChapterViewService {

    private final ChapterViewRepository views;
    private final PublicChapters publicChapters;
    private final RateLimiter rateLimiter;
    private final TransactionOperations transaction;

    ChapterViewService(ChapterViewRepository views, PublicChapters publicChapters, RateLimiter rateLimiter,
                       TransactionOperations transaction) {
        this.views = views;
        this.publicChapters = publicChapters;
        this.rateLimiter = rateLimiter;
        this.transaction = transaction;
    }

    public void record(UUID chapterId, SessionUser principal, String clientIp) {
        rateLimiter.acquire(RateLimitPolicy.VIEW_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.VIEW_ACCOUNT, principal.getName());
        transaction.executeWithoutResult(status -> {
            Chapter chapter = publicChapters.requirePublic(chapterId);
            UUID bookId = chapter.getBook().getId();
            if (!chapter.getBook().getAuthor().getId().equals(principal.id())) {
                views.count(principal.id(), chapterId, bookId);
            }
        });
    }
}
