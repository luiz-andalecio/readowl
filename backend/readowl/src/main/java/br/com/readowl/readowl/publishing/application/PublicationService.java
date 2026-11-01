package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.book.application.BookAccess;
import br.com.readowl.readowl.book.application.BookDetails;
import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.publishing.application.PublishingViews.ChapterView;
import br.com.readowl.readowl.publishing.application.PublishingViews.RevisionView;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.publishing.domain.ChapterDraft;
import br.com.readowl.readowl.publishing.domain.ChapterRevision;
import br.com.readowl.readowl.publishing.domain.ChapterState;
import br.com.readowl.readowl.publishing.infrastructure.ChapterRepository;
import br.com.readowl.readowl.publishing.infrastructure.ChapterRevisionRepository;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Conteúdo é público só com obra e capítulo publicados. Toda operação aqui bloqueia a obra: publicar a obra
 * e despublicar seu último capítulo ao mesmo tempo não pode deixar uma obra vazia no ar.
 */
@Service
@Transactional
public class PublicationService {

    private final BookAccess bookAccess;
    private final ChapterAccess chapterAccess;
    private final ChapterDraftService drafts;
    private final ChapterRepository chapters;
    private final ChapterRevisionRepository revisions;
    private final UserService users;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher events;
    private final ReadowlProperties.Limits limits;

    PublicationService(BookAccess bookAccess, ChapterAccess chapterAccess, ChapterDraftService drafts,
                       ChapterRepository chapters, ChapterRevisionRepository revisions, UserService users,
                       JsonMapper jsonMapper, ApplicationEventPublisher events, ReadowlProperties properties) {
        this.bookAccess = bookAccess;
        this.chapterAccess = chapterAccess;
        this.drafts = drafts;
        this.chapters = chapters;
        this.revisions = revisions;
        this.users = users;
        this.jsonMapper = jsonMapper;
        this.events = events;
        this.limits = properties.limits();
    }

    /**
     * Publica a versão do rascunho que o cliente informa ter visto. Um rascunho igual à revisão atual não
     * gera revisão nova. A revisão registra quem publicou, que pode ser um administrador.
     */
    public ChapterView publishChapter(UUID chapterId, long draftVersion, SessionUser principal) {
        Chapter chapter = chapterAccess.requireManageableLocked(chapterId, principal);
        if (chapter.isModerated()) {
            throw ApiException.conflict("CHAPTER_UNDER_MODERATION", "Este capítulo foi ocultado pela moderação.");
        }
        ChapterDraft draft = drafts.requireDraft(chapterId);
        ChapterDraftService.requireVersion(draft, draftVersion);

        JsonNode document = jsonMapper.readTree(draft.getDocument());
        ChapterDocuments.Rendered rendered = ChapterDocuments.render(document);
        if (rendered.isBlank()) {
            throw ApiException.badRequest("EMPTY_CHAPTER", "Escreva o capítulo antes de publicá-lo.");
        }
        boolean wasPublished = chapter.isPublished();
        ChapterRevision current = chapter.getPublishedRevision();
        boolean unchanged = current != null && jsonMapper.readTree(current.getDocument()).equals(document);
        if (unchanged) {
            chapter.publish(current);
        } else {
            int revisionNumber = revisions.findMaxRevisionNumber(chapterId) + 1;
            if (revisionNumber > limits.revisionsPerChapter()) {
                throw ApiException.conflict("REVISION_LIMIT_REACHED", "Este capítulo atingiu o limite de revisões publicadas.")
                        .withProperty("limit", limits.revisionsPerChapter());
            }
            chapter.publish(revisions.saveAndFlush(new ChapterRevision(chapter, revisionNumber, rendered.json(), draft.getFormatVersion(),
                    rendered.plainText(), rendered.html(), users.requireById(principal.id()))));
        }
        // Repetir a publicação sem alterações não conta como novidade para o catálogo.
        if (!wasPublished || !unchanged) {
            chapter.getBook().markChapterPublished(Instant.now());
        }
        if (current == null && chapter.getBook().isPublished()) {
            events.publishEvent(new ChapterFirstPublished(chapter.getBook().getId(), chapterId));
        }
        return ChapterView.from(chapters.saveAndFlush(chapter));
    }

    public ChapterView unpublishChapter(UUID chapterId, SessionUser principal) {
        Chapter chapter = chapterAccess.requireManageableLocked(chapterId, principal);
        Book book = chapter.getBook();
        if (chapter.isPublished() && book.isPublished()
                && chapters.countByBookIdAndState(book.getId(), ChapterState.PUBLISHED) == 1) {
            throw ApiException.conflict("LAST_PUBLISHED_CHAPTER",
                    "Este é o último capítulo publicado da obra. Despublique a obra antes.");
        }
        if (chapter.isPublished()) {
            chapter.unpublish();
        }
        return ChapterView.from(chapters.saveAndFlush(chapter));
    }

    public BookDetails publishBook(UUID bookId, SessionUser principal) {
        Book book = bookAccess.requireManageableLocked(bookId, principal);
        if (book.isModerated()) {
            throw ApiException.conflict("BOOK_UNDER_MODERATION", "Esta obra foi ocultada pela moderação.");
        }
        if (chapters.countByBookIdAndState(bookId, ChapterState.PUBLISHED) == 0) {
            throw ApiException.conflict("BOOK_WITHOUT_PUBLISHED_CHAPTERS", "Publique ao menos um capítulo antes da obra.");
        }
        book.publish();
        chapters.flush();
        return BookDetails.from(book);
    }

    public BookDetails unpublishBook(UUID bookId, SessionUser principal) {
        Book book = bookAccess.requireManageableLocked(bookId, principal);
        if (book.isPublished()) {
            book.unpublish();
        }
        chapters.flush();
        return BookDetails.from(book);
    }

    /**
     * Como em uma despublicação, uma obra no ar não pode ficar sem capítulos: nesse caso, oculte a obra.
     */
    public void hideChapter(UUID chapterId) {
        Chapter chapter = chapterAccess.requireLocked(chapterId);
        if (chapter.isModerated()) {
            throw alreadyHidden();
        }
        Book book = chapter.getBook();
        if (chapter.isPublished() && book.isPublished()
                && chapters.countByBookIdAndState(book.getId(), ChapterState.PUBLISHED) == 1) {
            throw ApiException.conflict("LAST_PUBLISHED_CHAPTER",
                    "Este é o último capítulo publicado da obra. Oculte a obra.");
        }
        chapter.hideByModeration(Instant.now());
        chapters.flush();
    }

    public void restoreChapter(UUID chapterId, SessionUser actor) {
        Chapter chapter = chapterAccess.requireLocked(chapterId);
        actor.requireIndependentReview(chapter.getBook().getAuthor().getId());
        if (!chapter.isModerated()) {
            throw notHidden();
        }
        chapter.restoreFromModeration();
        chapters.flush();
    }

    public void hideBook(UUID bookId) {
        Book book = bookAccess.requireLocked(bookId);
        if (book.isModerated()) {
            throw alreadyHidden();
        }
        book.hideByModeration(Instant.now());
        chapters.flush();
    }

    public void restoreBook(UUID bookId, SessionUser actor) {
        Book book = bookAccess.requireLocked(bookId);
        actor.requireIndependentReview(book.getAuthor().getId());
        if (!book.isModerated()) {
            throw notHidden();
        }
        book.restoreFromModeration();
        chapters.flush();
    }

    private static ApiException alreadyHidden() {
        return ApiException.conflict("ALREADY_HIDDEN", "Este conteúdo já está oculto.");
    }

    private static ApiException notHidden() {
        return ApiException.conflict("NOT_HIDDEN", "Este conteúdo não está oculto pela moderação.");
    }

    @Transactional(readOnly = true)
    public PageResponse<RevisionView> listRevisions(UUID chapterId, Pageable pageable, SessionUser principal) {
        Chapter chapter = chapterAccess.requireManageable(chapterId, principal);
        UUID publishedId = chapter.isPublished() && chapter.getPublishedRevision() != null
                ? chapter.getPublishedRevision().getId()
                : null;
        return PageResponse.from(revisions.findRowsByChapterId(chapterId, pageable)
                .map(revision -> RevisionView.from(revision, publishedId)));
    }
}
