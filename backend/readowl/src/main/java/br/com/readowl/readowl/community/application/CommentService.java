package br.com.readowl.readowl.community.application;

import br.com.readowl.readowl.book.application.PublicBooks;
import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.community.domain.Comment;
import br.com.readowl.readowl.community.infrastructure.CommentLikeRepository;
import br.com.readowl.readowl.community.infrastructure.CommentRepository;
import br.com.readowl.readowl.community.infrastructure.CommentRepository.CommentCount;
import br.com.readowl.readowl.publishing.application.PublicChapters;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.audit.AuditContext;
import br.com.readowl.readowl.shared.audit.AuditTrail;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Comentários são texto puro: o cliente deve exibi-los como texto, sem interpretar HTML. Os limites ficam
 * fora da transação, para que a tentativa conte mesmo quando a operação falha.
 */
@Service
public class CommentService {

    private static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(10);

    private final CommentRepository comments;
    private final CommentLikeRepository likes;
    private final PublicBooks publicBooks;
    private final PublicChapters publicChapters;
    private final UserService users;
    private final RateLimiter rateLimiter;
    private final TransactionOperations transaction;
    private final AuditTrail audit;

    CommentService(CommentRepository comments, CommentLikeRepository likes, PublicBooks publicBooks,
                   PublicChapters publicChapters, UserService users, RateLimiter rateLimiter,
                   TransactionOperations transaction, AuditTrail audit) {
        this.comments = comments;
        this.likes = likes;
        this.publicBooks = publicBooks;
        this.publicChapters = publicChapters;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.transaction = transaction;
        this.audit = audit;
    }

    /**
     * @param username ausente quando a conta foi excluída, porque não há mais perfil para abrir
     */
    public record CommentAuthor(String username, String name, String avatarUrl) {
    }

    /**
     * @param removed comentário principal excluído ou oculto que ainda tem respostas: sem texto nem autor
     */
    public record CommentResponse(UUID id, UUID bookId, UUID chapterId, UUID parentId, String content,
                                  CommentAuthor author, boolean removed, boolean edited, long replyCount,
                                  long likeCount, Instant createdAt, Instant updatedAt) {

        static CommentResponse from(Comment comment, long replyCount, long likeCount) {
            User author = comment.getUser();
            boolean removed = comment.isDeleted() || author.isSuspended();
            return new CommentResponse(comment.getId(), comment.getBook().getId(),
                    comment.getChapter() == null ? null : comment.getChapter().getId(),
                    comment.getParent() == null ? null : comment.getParent().getId(),
                    removed ? null : comment.getContent(),
                    removed ? null : new CommentAuthor(author.isDeleted() ? null : author.getUsername(), author.getName(), author.getAvatarUrl()), removed,
                    !removed && comment.getEditedAt() != null, replyCount, removed ? 0 : likeCount,
                    comment.getCreatedAt(), comment.getUpdatedAt());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> listOnBook(UUID bookId, Pageable pageable) {
        publicBooks.requireById(bookId);
        return withCounts(comments.findVisibleOnBook(bookId, pageable));
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> listOnChapter(UUID chapterId, Pageable pageable) {
        publicChapters.requirePublic(chapterId);
        return withCounts(comments.findVisibleOnChapter(chapterId, pageable));
    }

    /**
     * Funciona com o comentário principal excluído: as respostas continuam fazendo parte da conversa.
     */
    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> listReplies(UUID parentId, Pageable pageable) {
        comments.findById(parentId).filter(CommentService::isPublicThread).orElseThrow(CommentService::notFound);
        return withCounts(comments.findVisibleReplies(parentId, pageable));
    }

    public CommentResponse commentOnBook(UUID bookId, String content, SessionUser principal, String clientIp) {
        String text = clean(content);
        acquire(principal, clientIp);
        return transaction.execute(status -> create(publicBooks.requireById(bookId), null, null, text, principal));
    }

    public CommentResponse commentOnChapter(UUID chapterId, String content, SessionUser principal, String clientIp) {
        String text = clean(content);
        acquire(principal, clientIp);
        return transaction.execute(status -> {
            Chapter chapter = publicChapters.requirePublic(chapterId);
            return create(chapter.getBook(), chapter, null, text, principal);
        });
    }

    /**
     * As respostas têm um nível só: a obra e o capítulo vêm do comentário respondido, nunca do cliente.
     */
    public CommentResponse reply(UUID parentId, String content, SessionUser principal, String clientIp) {
        String text = clean(content);
        acquire(principal, clientIp);
        return transaction.execute(status -> {
            Comment parent = comments.findNotDeleted(parentId).filter(CommentService::isVisible)
                    .orElseThrow(CommentService::notFound);
            if (parent.getParent() != null) {
                throw ApiException.badRequest("NESTED_REPLY", "Responda ao comentário principal.");
            }
            return create(parent.getBook(), parent.getChapter(), parent, text, principal);
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
            if (!canOverride(comment, principal)) {
                requireVisible(comment);
                requireOwner(comment, principal);
            }
            if (!isOwner(comment, principal) && !text.equals(comment.getContent())) {
                audit.record(principal.id(), "COMMENT_EDITED", "comments/" + commentId,
                        Map.of("before", comment.getContent(), "after", text));
            }
            comment.edit(text, Instant.now());
            List<UUID> ids = List.of(comments.saveAndFlush(comment).getId());
            return CommentResponse.from(comment, replyCounts(ids).getOrDefault(comment.getId(), 0L),
                    likeCounts(ids).getOrDefault(comment.getId(), 0L));
        });
    }

    /**
     * Quem comentou pode excluir mesmo com o conteúdo fora do ar.
     */
    public void delete(UUID commentId, SessionUser principal) {
        transaction.executeWithoutResult(status -> {
            Comment comment = comments.findNotDeleted(commentId).orElseThrow(CommentService::notFound);
            if (!isOwner(comment, principal) && !canOverride(comment, principal)) {
                requireVisible(comment);
                requireOwner(comment, principal);
            }
            if (!isOwner(comment, principal)) {
                audit.record(principal.id(), "COMMENT_DELETED", "comments/" + commentId, Map.of("content", comment.getContent()));
            }
            comment.delete(Instant.now());
        });
    }

    private CommentResponse create(Book book, Chapter chapter, Comment parent, String text, SessionUser principal) {
        comments.lockCommentsOf(principal.id());
        if (isDuplicate(book, chapter, parent, text, principal)) {
            throw ApiException.conflict("DUPLICATE_COMMENT", "Você acabou de enviar este mesmo comentário.");
        }
        User author = users.requireById(principal.id());
        return CommentResponse.from(comments.saveAndFlush(new Comment(text, author, book, chapter, parent)), 0, 0);
    }

    private boolean isDuplicate(Book book, Chapter chapter, Comment parent, String text, SessionUser principal) {
        Instant since = Instant.now().minus(DUPLICATE_WINDOW);
        if (parent != null) {
            return comments.existsRecentReply(principal.id(), parent.getId(), text, since);
        }
        return chapter == null
                ? comments.existsRecentOnBook(principal.id(), book.getId(), text, since)
                : comments.existsRecentOnChapter(principal.id(), chapter.getId(), text, since);
    }

    /**
     * As contagens vêm em uma consulta por página, sem uma por comentário.
     */
    private PageResponse<CommentResponse> withCounts(Page<Comment> page) {
        List<UUID> ids = page.getContent().stream().map(Comment::getId).toList();
        Map<UUID, Long> replies = ids.isEmpty() ? Map.of() : replyCounts(ids);
        Map<UUID, Long> liked = ids.isEmpty() ? Map.of() : likeCounts(ids);
        return PageResponse.from(page.map(comment -> CommentResponse.from(comment,
                replies.getOrDefault(comment.getId(), 0L), liked.getOrDefault(comment.getId(), 0L))));
    }

    private Map<UUID, Long> replyCounts(List<UUID> ids) {
        return comments.countVisibleReplies(ids).stream().collect(Collectors.toMap(CommentCount::commentId, CommentCount::total));
    }

    private Map<UUID, Long> likeCounts(List<UUID> ids) {
        return likes.countByComment(ids).stream().collect(Collectors.toMap(CommentCount::commentId, CommentCount::total));
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

    private static boolean canOverride(Comment comment, SessionUser principal) {
        if (!principal.isAdministrator()) {
            return false;
        }
        if (!isOwner(comment, principal)) {
            AuditContext.markOverride();
        }
        return true;
    }

    private static void requireVisible(Comment comment) {
        if (!isVisible(comment)) {
            throw notFound();
        }
    }

    static boolean isVisible(Comment comment) {
        return !comment.getUser().isSuspended() && isOnPublicContent(comment);
    }

    private static boolean isPublicThread(Comment comment) {
        return comment.getParent() == null && isOnPublicContent(comment);
    }

    private static boolean isOnPublicContent(Comment comment) {
        Chapter chapter = comment.getChapter();
        return PublicBooks.isPublic(comment.getBook()) && (chapter == null || chapter.isPublished());
    }

    private static void requireOwner(Comment comment, SessionUser principal) {
        if (!isOwner(comment, principal)) {
            throw ApiException.forbidden("ACCESS_DENIED", "Você não tem permissão para alterar este comentário.");
        }
    }

    private static boolean isOwner(Comment comment, SessionUser principal) {
        return comment.getUser().getId().equals(principal.id());
    }

    static ApiException notFound() {
        return ApiException.notFound("COMMENT_NOT_FOUND", "Comentário não encontrado.");
    }
}
