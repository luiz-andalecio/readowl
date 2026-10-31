package br.com.readowl.readowl.community.infrastructure;

import br.com.readowl.readowl.community.domain.BookRating;
import br.com.readowl.readowl.community.domain.BookRatingId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface BookRatingRepository extends JpaRepository<BookRating, BookRatingId> {

    record RatingSummaryRow(Double average, long total) {
    }

    /**
     * A nota é sempre uma linha por usuário e obra, mesmo com pedidos simultâneos.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO book_ratings (user_id, book_id, score, created_at, updated_at)
            VALUES (:userId, :bookId, :score, now(), now())
            ON CONFLICT (user_id, book_id) DO UPDATE SET score = EXCLUDED.score, updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void record(UUID userId, UUID bookId, int score);

    /**
     * Remover duas vezes, ou em duas abas ao mesmo tempo, não é erro.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM book_ratings WHERE user_id = :userId AND book_id = :bookId", nativeQuery = true)
    void remove(UUID userId, UUID bookId);

    /**
     * Contas suspensas deixam de contar, e voltam se forem reativadas.
     */
    @Query("""
            select new br.com.readowl.readowl.community.infrastructure.BookRatingRepository$RatingSummaryRow(avg(r.score), count(r))
            from BookRating r join r.user u
            where r.id.bookId = :bookId and u.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """)
    RatingSummaryRow summarize(UUID bookId);
}
