package br.com.readowl.readowl.community.application;

import br.com.readowl.readowl.community.domain.Comment;
import br.com.readowl.readowl.community.infrastructure.CommentRepository;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Para o módulo de moderação, que verifica o papel de quem chama. Vê comentários em qualquer estado.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CommentModeration {

    private final CommentRepository comments;

    CommentModeration(CommentRepository comments) {
        this.comments = comments;
    }

    public record ModeratedCommentAuthor(UUID id, String username, String name) {
    }

    public record ModeratedComment(UUID id, UUID bookId, UUID chapterId, UUID parentId, String content,
                                   ModeratedCommentAuthor author, Instant deletedAt, Instant moderatedAt,
                                   Instant createdAt, Instant updatedAt) {

        static ModeratedComment from(Comment comment) {
            User author = comment.getUser();
            return new ModeratedComment(comment.getId(), comment.getBook().getId(),
                    comment.getChapter() == null ? null : comment.getChapter().getId(),
                    comment.getParent() == null ? null : comment.getParent().getId(), comment.getContent(),
                    new ModeratedCommentAuthor(author.getId(), author.getUsername(), author.getName()),
                    comment.getDeletedAt(), comment.getModeratedAt(), comment.getCreatedAt(), comment.getUpdatedAt());
        }
    }

    /**
     * Inclui respostas, comentários de capítulos, excluídos e de obras fora do ar.
     */
    public PageResponse<ModeratedComment> listOnBook(UUID bookId, Pageable pageable) {
        return PageResponse.from(comments.findAllOnBook(bookId, pageable).map(ModeratedComment::from));
    }

    public PageResponse<ModeratedComment> listByUser(UUID userId, Pageable pageable) {
        return PageResponse.from(comments.findAllByUser(userId, pageable).map(ModeratedComment::from));
    }

    public Comment requireVisible(UUID commentId) {
        return comments.findNotDeleted(commentId).filter(CommentService::isVisible).orElseThrow(CommentService::notFound);
    }

    /**
     * Um comentário que a própria pessoa excluiu não tem o que ocultar.
     */
    public void hide(UUID commentId) {
        Comment comment = comments.findById(commentId).orElseThrow(CommentService::notFound);
        if (comment.isDeleted()) {
            throw ApiException.conflict("ALREADY_HIDDEN", "Este conteúdo já está oculto.");
        }
        comment.hideByModeration(Instant.now());
        comments.flush();
    }

    /**
     * Só desfaz a moderação: não traz de volta o que a própria pessoa excluiu.
     */
    public void restore(UUID commentId) {
        Comment comment = comments.findById(commentId).orElseThrow(CommentService::notFound);
        if (!comment.isModerated()) {
            throw ApiException.conflict("NOT_HIDDEN", "Este conteúdo não está oculto pela moderação.");
        }
        comment.restoreFromModeration();
        comments.flush();
    }
}
