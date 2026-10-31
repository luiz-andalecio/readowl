package br.com.readowl.readowl.community.infrastructure;

import br.com.readowl.readowl.community.domain.CommentLike;
import br.com.readowl.readowl.community.domain.CommentLikeId;
import br.com.readowl.readowl.community.infrastructure.CommentRepository.CommentCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommentLikeRepository extends JpaRepository<CommentLike, CommentLikeId> {

    /**
     * Curtir de novo, ou em duas abas ao mesmo tempo, não é erro nem conta duas vezes.
     */
    @Modifying
    @Query(value = """
            INSERT INTO comment_likes (user_id, comment_id, created_at) VALUES (:userId, :commentId, now())
            ON CONFLICT (user_id, comment_id) DO NOTHING
            """, nativeQuery = true)
    void like(UUID userId, UUID commentId);

    @Modifying
    @Query(value = "DELETE FROM comment_likes WHERE user_id = :userId AND comment_id = :commentId", nativeQuery = true)
    void unlike(UUID userId, UUID commentId);

    /**
     * Contas suspensas deixam de contar, e voltam se forem reativadas.
     */
    @Query("""
            select new br.com.readowl.readowl.community.infrastructure.CommentRepository$CommentCount(l.id.commentId, count(l))
            from CommentLike l join l.user u
            where l.id.commentId in :commentIds and u.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            group by l.id.commentId
            """)
    List<CommentCount> countByComment(Collection<UUID> commentIds);

    @Query("select l.id.commentId from CommentLike l where l.id.userId = :userId and l.id.commentId in :commentIds")
    List<UUID> findLikedAmong(UUID userId, Collection<UUID> commentIds);
}
