package br.com.readowl.readowl.community.application;

import br.com.readowl.readowl.book.application.PublicBooks;
import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.community.domain.BookRating;
import br.com.readowl.readowl.community.domain.BookRatingId;
import br.com.readowl.readowl.community.infrastructure.BookRatingRepository;
import br.com.readowl.readowl.community.infrastructure.BookRatingRepository.RatingSummaryRow;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * O resumo é sempre calculado a partir das notas gravadas. O cliente envia só a própria nota.
 */
@Service
public class RatingService {

    private static final int AVERAGE_DECIMALS = 2;

    private final BookRatingRepository ratings;
    private final PublicBooks publicBooks;
    private final RateLimiter rateLimiter;
    private final TransactionOperations transaction;

    RatingService(BookRatingRepository ratings, PublicBooks publicBooks, RateLimiter rateLimiter,
                  TransactionOperations transaction) {
        this.ratings = ratings;
        this.publicBooks = publicBooks;
        this.rateLimiter = rateLimiter;
        this.transaction = transaction;
    }

    public record RatingSummary(BigDecimal average, long count) {
    }

    public record MyRating(Integer score, RatingSummary summary) {
    }

    @Transactional(readOnly = true)
    public RatingSummary summary(UUID bookId) {
        publicBooks.requireById(bookId);
        return summarize(bookId);
    }

    @Transactional(readOnly = true)
    public MyRating mine(UUID bookId, SessionUser principal) {
        publicBooks.requireById(bookId);
        return currentState(bookId, principal);
    }

    /**
     * O autor não avalia a própria obra, o que inflaria a nota.
     */
    public MyRating rate(UUID bookId, int score, SessionUser principal, String clientIp) {
        rateLimiter.acquire(RateLimitPolicy.INTERACTION_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.INTERACTION_ACCOUNT, principal.getName());
        return transaction.execute(status -> {
            Book book = publicBooks.requireById(bookId);
            if (book.getAuthor().getId().equals(principal.id())) {
                throw ApiException.forbidden("OWN_BOOK_RATING", "O autor não pode avaliar a própria obra.");
            }
            ratings.record(principal.id(), bookId, score);
            return currentState(bookId, principal);
        });
    }

    /**
     * Funciona mesmo com a obra fora do ar: a nota é do usuário.
     */
    @Transactional
    public void remove(UUID bookId, SessionUser principal) {
        ratings.remove(principal.id(), bookId);
    }

    private MyRating currentState(UUID bookId, SessionUser principal) {
        Integer score = ratings.findById(new BookRatingId(principal.id(), bookId)).map(BookRating::getScore).orElse(null);
        return new MyRating(score, summarize(bookId));
    }

    private RatingSummary summarize(UUID bookId) {
        RatingSummaryRow row = ratings.summarize(bookId);
        return new RatingSummary(row.average() == null ? null
                : BigDecimal.valueOf(row.average()).setScale(AVERAGE_DECIMALS, RoundingMode.HALF_UP), row.total());
    }
}
