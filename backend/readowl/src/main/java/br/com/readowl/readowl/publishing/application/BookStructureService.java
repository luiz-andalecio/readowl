package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.book.application.BookAccess;
import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.publishing.application.PublishingViews.BookContents;
import br.com.readowl.readowl.publishing.application.PublishingViews.ChapterView;
import br.com.readowl.readowl.publishing.application.PublishingViews.VolumeView;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.publishing.domain.ChapterDraft;
import br.com.readowl.readowl.publishing.domain.Volume;
import br.com.readowl.readowl.publishing.infrastructure.ChapterDraftRepository;
import br.com.readowl.readowl.publishing.infrastructure.ChapterRepository;
import br.com.readowl.readowl.publishing.infrastructure.ChapterRepository.ChapterSlugRow;
import br.com.readowl.readowl.publishing.infrastructure.VolumeRepository;
import br.com.readowl.readowl.shared.audit.AuditTrail;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.text.Slugs;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Volumes e capítulos de uma obra. As alterações bloqueiam a obra, para que numeração e slugs sejam
 * calculados sem concorrência.
 */
@Service
@Transactional
public class BookStructureService {

    private static final String DEFAULT_CHAPTER_SLUG = "capitulo";

    private final BookAccess bookAccess;
    private final ChapterAccess chapterAccess;
    private final VolumeRepository volumes;
    private final ChapterRepository chapters;
    private final ChapterDraftRepository drafts;
    private final ReadowlProperties.Limits limits;
    private final AuditTrail audit;

    BookStructureService(BookAccess bookAccess, ChapterAccess chapterAccess, VolumeRepository volumes,
                         ChapterRepository chapters, ChapterDraftRepository drafts, ReadowlProperties properties,
                         AuditTrail audit) {
        this.bookAccess = bookAccess;
        this.chapterAccess = chapterAccess;
        this.volumes = volumes;
        this.chapters = chapters;
        this.drafts = drafts;
        this.limits = properties.limits();
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public BookContents contents(UUID bookId, SessionUser principal) {
        bookAccess.requireManageable(bookId, principal);
        return new BookContents(
                volumes.findByBookIdOrderByDisplayOrder(bookId).stream().map(VolumeView::from).toList(),
                chapters.findByBookIdOrderByDisplayOrder(bookId).stream().map(ChapterView::from).toList());
    }

    public VolumeView createVolume(UUID bookId, String title, SessionUser principal) {
        Book book = bookAccess.requireManageableLocked(bookId, principal);
        if (volumes.countByBookId(bookId) >= limits.volumesPerBook()) {
            throw ApiException.conflict("VOLUME_LIMIT_REACHED", "Esta obra atingiu o limite de volumes.")
                    .withProperty("limit", limits.volumesPerBook());
        }
        return VolumeView.from(volumes.saveAndFlush(new Volume(book, title.trim(), volumes.findMaxOrder(bookId) + 1)));
    }

    public VolumeView renameVolume(UUID volumeId, String title, Long version, SessionUser principal) {
        Volume volume = chapterAccess.requireManageableVolumeLocked(volumeId, principal);
        if (version != null && version != volume.getVersion()) {
            throw ApiException.versionConflict(volume.getVersion());
        }
        volume.rename(title.trim());
        return VolumeView.from(volumes.saveAndFlush(volume));
    }

    public void deleteVolume(UUID volumeId, SessionUser principal) {
        Volume volume = chapterAccess.requireManageableVolumeLocked(volumeId, principal);
        if (chapters.existsByVolumeId(volumeId)) {
            throw ApiException.conflict("VOLUME_NOT_EMPTY", "Mova ou exclua os capítulos deste volume antes de excluí-lo.");
        }
        volumes.delete(volume);
    }

    /**
     * Recebe todos os volumes da obra na nova ordem. A restrição de ordem é verificada só no commit,
     * o que permite trocar posições.
     */
    public List<VolumeView> reorderVolumes(UUID bookId, List<UUID> volumeIds, SessionUser principal) {
        bookAccess.requireManageableLocked(bookId, principal);
        List<Volume> current = volumes.findByBookIdOrderByDisplayOrder(bookId);
        Set<UUID> currentIds = current.stream().map(Volume::getId).collect(Collectors.toSet());
        if (volumeIds.size() != current.size() || !currentIds.equals(new HashSet<>(volumeIds))) {
            throw invalidOrder("Informe todos os volumes da obra, sem repetir.");
        }
        Map<UUID, Volume> byId = current.stream().collect(Collectors.toMap(Volume::getId, Function.identity()));
        for (int position = 0; position < volumeIds.size(); position++) {
            byId.get(volumeIds.get(position)).moveTo(position + 1);
        }
        volumes.flush();
        return volumes.findByBookIdOrderByDisplayOrder(bookId).stream().map(VolumeView::from).toList();
    }

    public ChapterView createChapter(UUID bookId, String title, UUID volumeId, SessionUser principal) {
        Book book = bookAccess.requireManageableLocked(bookId, principal);
        if (chapters.countByBookId(bookId) >= limits.chaptersPerBook()) {
            throw ApiException.conflict("CHAPTER_LIMIT_REACHED", "Esta obra atingiu o limite de capítulos.")
                    .withProperty("limit", limits.chaptersPerBook());
        }
        Volume volume = volumeId == null ? null : requireVolumeOfBook(volumeId, bookId);
        int order = (volumeId == null
                ? chapters.findMaxOrderWithoutVolume(bookId)
                : chapters.findMaxOrderInVolume(bookId, volumeId)) + 1;
        String cleanTitle = title.trim();
        Chapter chapter = chapters.saveAndFlush(
                new Chapter(book, volume, cleanTitle, uniqueSlug(bookId, cleanTitle, null), order));
        drafts.save(new ChapterDraft(chapter, ChapterDocuments.EMPTY_DOCUMENT, ChapterDocuments.FORMAT_VERSION));
        return ChapterView.from(chapter);
    }

    public ChapterView renameChapter(UUID chapterId, String title, Long version, SessionUser principal) {
        Chapter chapter = chapterAccess.requireManageableLocked(chapterId, principal);
        if (version != null && version != chapter.getVersion()) {
            throw ApiException.versionConflict(chapter.getVersion());
        }
        String cleanTitle = title.trim();
        if (!cleanTitle.equals(chapter.getTitle())) {
            chapter.rename(cleanTitle, uniqueSlug(chapter.getBook().getId(), cleanTitle, chapterId));
        }
        return ChapterView.from(chapters.saveAndFlush(chapter));
    }

    /**
     * Excluir apaga comentários e visualizações em cascata: um capítulo no ar precisa ser despublicado antes.
     * Um capítulo oculto pela moderação é prova até ser restaurado, e vale também para administradores.
     */
    public void deleteChapter(UUID chapterId, SessionUser principal) {
        Chapter chapter = chapterAccess.requireManageableLocked(chapterId, principal);
        if (chapter.isModerated()) {
            throw ApiException.conflict("CHAPTER_UNDER_MODERATION", "Este capítulo foi ocultado pela moderação.");
        }
        if (chapter.isPublished()) {
            throw ApiException.conflict("CHAPTER_PUBLISHED", "Despublique o capítulo antes de excluí-lo.");
        }
        Book book = chapter.getBook();
        if (!book.getAuthor().getId().equals(principal.id())) {
            audit.record(principal.id(), "CHAPTER_DELETED", "chapters/" + chapterId, Map.of("title", chapter.getTitle(),
                    "slug", chapter.getSlug(), "bookId", book.getId()));
        }
        chapters.delete(chapter);
    }

    /**
     * Define a ordem de um volume, ou dos capítulos sem volume quando volumeId é nulo. Precisa incluir
     * todos os capítulos que já estão ali; os demais informados são movidos para cá.
     */
    public List<ChapterView> reorderChapters(UUID bookId, UUID volumeId, List<UUID> chapterIds, SessionUser principal) {
        bookAccess.requireManageableLocked(bookId, principal);
        Volume volume = volumeId == null ? null : requireVolumeOfBook(volumeId, bookId);
        Set<UUID> requested = new HashSet<>(chapterIds);
        if (requested.size() != chapterIds.size()) {
            throw invalidOrder("Há capítulos repetidos na lista.");
        }
        List<Chapter> found = chapters.findByBookIdAndIdIn(bookId, requested);
        if (found.size() != requested.size()) {
            throw invalidOrder("Todos os capítulos precisam pertencer à obra.");
        }
        List<Chapter> alreadyThere = volumeId == null
                ? chapters.findByBookIdAndVolumeIsNull(bookId)
                : chapters.findByBookIdAndVolumeId(bookId, volumeId);
        if (!alreadyThere.stream().map(Chapter::getId).allMatch(requested::contains)) {
            throw invalidOrder("Informe todos os capítulos que já estão neste volume.");
        }
        Map<UUID, Chapter> byId = found.stream().collect(Collectors.toMap(Chapter::getId, Function.identity()));
        for (int position = 0; position < chapterIds.size(); position++) {
            byId.get(chapterIds.get(position)).moveTo(volume, position + 1);
        }
        chapters.flush();
        return chapterIds.stream().map(byId::get).map(ChapterView::from).toList();
    }

    private Volume requireVolumeOfBook(UUID volumeId, UUID bookId) {
        return volumes.findByIdAndBookId(volumeId, bookId)
                .orElseThrow(() -> ApiException.badRequest("VOLUME_NOT_IN_BOOK", "O volume não pertence a esta obra."));
    }

    /**
     * Capítulos podem repetir títulos, como "Prólogo" em dois volumes: o slug recebe um número.
     *
     * @param chapterId capítulo sendo renomeado, que não conflita consigo mesmo; nulo em um capítulo novo
     */
    private String uniqueSlug(UUID bookId, String title, UUID chapterId) {
        String slug = Slugs.slugify(title);
        String base = slug.isEmpty() ? DEFAULT_CHAPTER_SLUG : slug;
        Set<String> taken = chapters.findSimilarSlugs(bookId, base, base + "-%").stream()
                .filter(row -> !row.id().equals(chapterId))
                .map(ChapterSlugRow::slug)
                .collect(Collectors.toSet());
        String candidate = base;
        for (int suffix = 2; taken.contains(candidate); suffix++) {
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    private static ApiException invalidOrder(String detail) {
        return ApiException.badRequest("INVALID_ORDER", detail);
    }
}
