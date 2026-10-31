package br.com.readowl.readowl.community.application;

import br.com.readowl.readowl.book.application.PublicBooks;
import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.community.domain.Comment;
import br.com.readowl.readowl.community.infrastructure.CommentRepository;
import br.com.readowl.readowl.publishing.application.PublicChapters;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Comentários são texto puro: o cliente deve exibi-los como texto, sem interpretar HTML. Os limites ficam
 * fora da transação, para que a tentativa conte mesmo quando a operação falha.
 */
@Service
public class CommentService {

    private static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(10);

    private final CommentRepository comments;
    private final PublicBooks publicBooks;
    private final PublicChapters publicChapters;
    private final UserService users;
    private final RateLimiter rateLimiter;
    private final TransactionOperations transaction;

    CommentService(CommentRepository comments, PublicBooks publicBooks, PublicChapters publicChapters,
                   UserService users, RateLimiter rateLimiter, TransactionOperations transaction) {
        this.comments = comments;
        this.publicBooks = publicBooks;
        this.publicChapters = publicChapters;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.transaction = transaction;
    }

    public record CommentAuthor(String username, String name) {
    }

    public record CommentResponse(UUID id, UUID bookId, UUID chapterId, String content, CommentAuthor author,
                                  boolean edited, Instant createdAt, Instant updatedAt) {

        static CommentResponse from(Comment comment) {
            User author = comment.getUser();
            return new CommentResponse(comment.getId(), comment.getBook().getId(),
                    comment.getChapter() == null ? null : comment.getChapter().getId(), comment.getContent(),
                    new CommentAuthor(author.getUsername(), author.getName()),
                    comment.getUpdatedAt().isAfter(comment.getCreatedAt()), comment.getCreatedAt(), comment.getUpdatedAt());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> listOnBook(UUID bookId, Pageable pageable) {
        publicBooks.requireById(bookId);
        return PageResponse.from(comments.findVisibleOnBook(bookId, pageable).map(CommentResponse::from));
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> listOnChapter(UUID chapterId, Pageable pageable) {
        publicChapters.requirePublic(chapterId);
        return PageResponse.from(comments.findVisibleOnChapter(chapterId, pageable).map(CommentResponse::from));
    }

    public CommentResponse commentOnBook(UUID bookId, String content, SessionUser principal, String clientIp) {
        String text = clean(content);
        acquire(principal, clientIp);
        return transaction.execute(status -> create(publicBooks.requireById(bookId), null, text, principal));
    }

    public CommentResponse commentOnChapter(UUID chapterId, String content, SessionUser principal, String clientIp) {
        String text = clean(content);
        acquire(principal, clientIp);
        return transaction.execute(status -> {
            Chapter chapter = publicChapters.requirePublic(chapterId);
            return create(chapter.getBook(), chapter, text, principal);
        });
    }

    /**
     * Quem comentou só edita enquanto o conteúdo estiver no ar. A administração tem acesso total.
     */
    public CommentResponse edit(UUID commentId, String content, SessionUser principal, String clientIp) {
        String text = clean(content);
        acquire(principal, clientIp);
        return transaction.execute(status -> {
            Comment comment = comments.findNotDeleted(commentId).orElseThrow(CommentService::notFound);
            if (!principal.isAdministrator()) {
                requireVisible(comment);
                requireOwner(comment, principal);
            }
            comment.edit(text);
            return CommentResponse.from(comments.saveAndFlush(comment));
        });
    }

    /**
     * Quem comentou pode excluir mesmo com o conteúdo fora do ar.
     */
    public void delete(UUID commentId, SessionUser principal) {
        transaction.executeWithoutResult(status -> {
            Comment comment = comments.findNotDeleted(commentId).orElseThrow(CommentService::notFound);
            if (!principal.isAdministrator() && !isOwner(comment, principal)) {
                requireVisible(comment);
                requireOwner(comment, principal);
            }
            comment.delete(Instant.now());
        });
    }

    private CommentResponse create(Book book, Chapter chapter, String text, SessionUser principal) {
        comments.lockCommentsOf(principal.id());
        Instant since = Instant.now().minus(DUPLICATE_WINDOW);
        boolean duplicate = chapter == null
                ? comments.existsRecentOnBook(principal.id(), book.getId(), text, since)
                : comments.existsRecentOnChapter(principal.id(), chapter.getId(), text, since);
        if (duplicate) {
            throw ApiException.conflict("DUPLICATE_COMMENT", "Você acabou de enviar este mesmo comentário.");
        }
        User author = users.requireById(principal.id());
        return CommentResponse.from(comments.saveAndFlush(new Comment(text, author, book, chapter, null)));
    }

    private void acquire(SessionUser principal, String clientIp) {
        rateLimiter.acquire(RateLimitPolicy.COMMENT_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.COMMENT_ACCOUNT, principal.getName());
    }

    /**
     * Caracteres de controle não têm uso em um comentário, exceto as quebras de linha e a tabulação.
     */
    private static String clean(String content) {
        String text = content.trim();
        boolean hasControl = text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t');
        if (hasControl) {
            throw ApiException.badRequest("INVALID_COMMENT", "O comentário tem caracteres não permitidos.");
        }
        return text;
    }

    private static void requireVisible(Comment comment) {
        Chapter chapter = comment.getChapter();
        if (!comment.getUser().isActive() || !PublicBooks.isPublic(comment.getBook())
                || (chapter != null && !chapter.isPublished())) {
            throw notFound();
        }
    }

    private static void requireOwner(Comment comment, SessionUser principal) {
        if (!isOwner(comment, principal)) {
            throw ApiException.forbidden("ACCESS_DENIED", "Você não tem permissão para alterar este comentário.");
        }
    }

    private static boolean isOwner(Comment comment, SessionUser principal) {
        return comment.getUser().getId().equals(principal.id());
    }

    private static ApiException notFound() {
        return ApiException.notFound("COMMENT_NOT_FOUND", "Comentário não encontrado.");
    }
}
