package br.com.readowl.readowl.community.infrastructure;

import br.com.readowl.readowl.community.domain.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /**
     * Comentários de contas suspensas não aparecem, e voltam se a conta for reativada.
     */
    @Query(value = """
            select c from Comment c join fetch c.user u
            where c.book.id = :bookId and c.chapter is null and c.deletedAt is null
              and u.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """, countQuery = """
            select count(c) from Comment c join c.user u
            where c.book.id = :bookId and c.chapter is null and c.deletedAt is null
              and u.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """)
    Page<Comment> findVisibleOnBook(UUID bookId, Pageable pageable);

    @Query(value = """
            select c from Comment c join fetch c.user u
            where c.chapter.id = :chapterId and c.deletedAt is null
              and u.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """, countQuery = """
            select count(c) from Comment c join c.user u
            where c.chapter.id = :chapterId and c.deletedAt is null
              and u.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """)
    Page<Comment> findVisibleOnChapter(UUID chapterId, Pageable pageable);

    @Query("select c from Comment c where c.id = :id and c.deletedAt is null")
    Optional<Comment> findNotDeleted(UUID id);

    /**
     * O bloqueio dura até o fim da transação e serializa só os comentários de um mesmo usuário, para que
     * dois envios simultâneos do mesmo texto não passem juntos pela verificação de repetição.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text), 0))) AS locked",
            nativeQuery = true)
    int lockCommentsOf(UUID userId);

    @Query("""
            select count(c) > 0 from Comment c
            where c.user.id = :userId and c.book.id = :bookId and c.chapter is null and c.content = :content
              and c.deletedAt is null and c.createdAt > :since
            """)
    boolean existsRecentOnBook(UUID userId, UUID bookId, String content, Instant since);

    @Query("""
            select count(c) > 0 from Comment c
            where c.user.id = :userId and c.chapter.id = :chapterId and c.content = :content
              and c.deletedAt is null and c.createdAt > :since
            """)
    boolean existsRecentOnChapter(UUID userId, UUID chapterId, String content, Instant since);
}
