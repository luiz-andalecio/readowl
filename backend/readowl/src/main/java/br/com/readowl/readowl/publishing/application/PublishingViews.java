package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.publishing.domain.ChapterDraft;
import br.com.readowl.readowl.publishing.domain.ChapterRevision;
import br.com.readowl.readowl.publishing.domain.ChapterState;
import br.com.readowl.readowl.publishing.domain.Volume;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Respostas do módulo de publicação. Entidades não saem da camada de aplicação.
 */
public final class PublishingViews {

    private PublishingViews() {
    }

    public record VolumeView(UUID id, String title, int displayOrder) {

        static VolumeView from(Volume volume) {
            return new VolumeView(volume.getId(), volume.getTitle(), volume.getDisplayOrder());
        }
    }

    /**
     * Só as rotas de gerenciamento usam esta resposta: a contagem por capítulo é restrita ao autor e à administração.
     */
    public record ChapterView(UUID id, String title, String slug, ChapterState state, boolean moderated,
                              int displayOrder, UUID volumeId, long viewCount, Instant createdAt, Instant updatedAt) {

        static ChapterView from(Chapter chapter) {
            return new ChapterView(chapter.getId(), chapter.getTitle(), chapter.getSlug(), chapter.getState(),
                    chapter.isModerated(), chapter.getDisplayOrder(),
                    chapter.getVolume() == null ? null : chapter.getVolume().getId(),
                    chapter.getViewCount(), chapter.getCreatedAt(), chapter.getUpdatedAt());
        }
    }

    public record BookContents(List<VolumeView> volumes, List<ChapterView> chapters) {
    }

    public record DraftView(UUID chapterId, long version, JsonNode document, int formatVersion, Instant updatedAt) {

        static DraftView from(ChapterDraft draft, JsonNode document) {
            return new DraftView(draft.getChapterId(), draft.getVersion(), document, draft.getFormatVersion(),
                    draft.getUpdatedAt());
        }
    }

    public record RevisionView(int revisionNumber, int formatVersion, String createdByUsername, Instant createdAt,
                               boolean published) {

        static RevisionView from(ChapterRevision revision, UUID publishedRevisionId) {
            return new RevisionView(revision.getRevisionNumber(), revision.getFormatVersion(),
                    revision.getCreatedBy().getUsername(), revision.getCreatedAt(),
                    revision.getId().equals(publishedRevisionId));
        }
    }
}
