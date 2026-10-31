package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.book.application.BookAccess;
import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.publishing.domain.Volume;
import br.com.readowl.readowl.publishing.infrastructure.ChapterRepository;
import br.com.readowl.readowl.publishing.infrastructure.VolumeRepository;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Localiza capítulos e volumes já verificando a permissão sobre a obra a que pertencem.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ChapterAccess {

    private final ChapterRepository chapters;
    private final VolumeRepository volumes;
    private final BookAccess bookAccess;

    ChapterAccess(ChapterRepository chapters, VolumeRepository volumes, BookAccess bookAccess) {
        this.chapters = chapters;
        this.volumes = volumes;
        this.bookAccess = bookAccess;
    }

    /**
     * Quem não pode gerenciar só recebe 403 sobre conteúdo público; o restante responde como inexistente.
     */
    Chapter requireManageable(UUID chapterId, SessionUser principal) {
        Chapter chapter = chapters.findById(chapterId).orElseThrow(ChapterAccess::chapterNotFound);
        requirePermission(chapter, principal);
        return chapter;
    }

    /**
     * Para alterações que dependem do conjunto de capítulos. A obra é bloqueada antes de qualquer leitura,
     * para que as decisões não usem dados anteriores a outra operação em andamento.
     */
    Chapter requireManageableLocked(UUID chapterId, SessionUser principal) {
        bookAccess.lock(chapters.findBookIdById(chapterId).orElseThrow(ChapterAccess::chapterNotFound));
        return requireManageable(chapterId, principal);
    }

    Volume requireManageableVolumeLocked(UUID volumeId, SessionUser principal) {
        bookAccess.lock(volumes.findBookIdById(volumeId).orElseThrow(ChapterAccess::volumeNotFound));
        Volume volume = volumes.findById(volumeId).orElseThrow(ChapterAccess::volumeNotFound);
        Book book = volume.getBook();
        if (BookAccess.canManage(book, principal)) {
            return volume;
        }
        if (book.isPublished()) {
            throw ApiException.forbidden("ACCESS_DENIED", "Você não tem permissão para alterar este volume.");
        }
        throw volumeNotFound();
    }

    private static void requirePermission(Chapter chapter, SessionUser principal) {
        Book book = chapter.getBook();
        if (BookAccess.canManage(book, principal)) {
            return;
        }
        if (book.isPublished() && chapter.isPublished()) {
            throw ApiException.forbidden("ACCESS_DENIED", "Você não tem permissão para alterar este capítulo.");
        }
        throw chapterNotFound();
    }

    private static ApiException volumeNotFound() {
        return ApiException.notFound("VOLUME_NOT_FOUND", "Volume não encontrado.");
    }

    private static ApiException chapterNotFound() {
        return ApiException.notFound("CHAPTER_NOT_FOUND", "Capítulo não encontrado.");
    }
}
