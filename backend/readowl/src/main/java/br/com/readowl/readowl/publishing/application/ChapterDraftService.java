package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.media.application.ExternalImages;
import br.com.readowl.readowl.publishing.application.PublishingViews.DraftView;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.publishing.domain.ChapterDraft;
import br.com.readowl.readowl.publishing.infrastructure.ChapterDraftRepository;
import br.com.readowl.readowl.shared.audit.AuditContext;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * O salvamento automático altera apenas o rascunho: o conteúdo publicado fica nas revisões.
 */
@Service
@Transactional
public class ChapterDraftService {

    private final ChapterAccess access;
    private final ChapterDraftRepository drafts;
    private final JsonMapper jsonMapper;
    private final StorageQuota storage;
    private final ExternalImages images;

    ChapterDraftService(ChapterAccess access, ChapterDraftRepository drafts, JsonMapper jsonMapper, StorageQuota storage,
                        ExternalImages images) {
        this.images = images;
        this.access = access;
        this.drafts = drafts;
        this.jsonMapper = jsonMapper;
        this.storage = storage;
    }

    /**
     * Um rascunho é privado: a administração pode lê-lo, e essa leitura fica na auditoria.
     */
    @Transactional(readOnly = true)
    public DraftView get(UUID chapterId, SessionUser principal) {
        Chapter chapter = access.requireManageable(chapterId, principal);
        if (!chapter.getBook().getAuthor().getId().equals(principal.id())) {
            AuditContext.markSensitiveRead();
        }
        ChapterDraft draft = requireDraft(chapter.getId());
        return DraftView.from(draft, jsonMapper.readTree(draft.getDocument()));
    }

    /**
     * Compara com a versão que o cliente leu: outra aba pode ter salvo depois dela.
     */
    public DraftView save(UUID chapterId, long expectedVersion, JsonNode document, SessionUser principal) {
        Chapter chapter = access.requireManageable(chapterId, principal);
        ChapterDraft draft = requireDraft(chapter.getId());
        requireVersion(draft, expectedVersion);
        ChapterDocuments.Rendered rendered = ChapterDocuments.render(document, images::isAllowed);
        storage.requireRoomForDraft(chapter.getBook().getAuthor().getId(), rendered.json().length());
        draft.replaceDocument(rendered.json(), ChapterDocuments.FORMAT_VERSION);
        ChapterDraft saved = drafts.saveAndFlush(draft);
        return DraftView.from(saved, document);
    }

    ChapterDraft requireDraft(UUID chapterId) {
        return drafts.findById(chapterId)
                .orElseThrow(() -> ApiException.notFound("CHAPTER_NOT_FOUND", "Capítulo não encontrado."));
    }

    static void requireVersion(ChapterDraft draft, long expectedVersion) {
        if (draft.getVersion() != expectedVersion) {
            throw ApiException.conflict("DRAFT_CONFLICT", "O rascunho foi alterado em outra aba ou dispositivo.")
                    .withProperty("currentVersion", draft.getVersion());
        }
    }
}
