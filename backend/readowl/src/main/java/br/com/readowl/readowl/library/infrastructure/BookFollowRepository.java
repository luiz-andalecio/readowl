package br.com.readowl.readowl.library.infrastructure;

import br.com.readowl.readowl.library.domain.BookFollow;
import br.com.readowl.readowl.library.domain.BookFollowId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface BookFollowRepository extends JpaRepository<BookFollow, BookFollowId> {

    /**
     * Seguir de novo, ou em duas abas ao mesmo tempo, não é erro.
     */
    @Modifying
    @Query(value = """
            INSERT INTO book_follows (user_id, book_id, created_at) VALUES (:userId, :bookId, now())
            ON CONFLICT (user_id, book_id) DO NOTHING
            """, nativeQuery = true)
    void follow(UUID userId, UUID bookId);

    /**
     * Deixar de seguir duas vezes, ou em duas abas ao mesmo tempo, também não é erro.
     */
    @Modifying
    @Query(value = "DELETE FROM book_follows WHERE user_id = :userId AND book_id = :bookId", nativeQuery = true)
    void unfollow(UUID userId, UUID bookId);

    /**
     * Uma obra que saiu do ar não aparece, mas continua seguida: volta à lista se for republicada.
     */
    @Query(value = """
            select f from BookFollow f join fetch f.book b join fetch b.author a
            where f.user.id = :userId
              and b.publicationState = br.com.readowl.readowl.book.domain.PublicationState.PUBLISHED
              and a.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """, countQuery = """
            select count(f) from BookFollow f join f.book b join b.author a
            where f.user.id = :userId
              and b.publicationState = br.com.readowl.readowl.book.domain.PublicationState.PUBLISHED
              and a.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """)
    Page<BookFollow> findPublicByUser(UUID userId, Pageable pageable);
}
