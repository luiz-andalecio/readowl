package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.book.application.PublicBooks;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.publishing.infrastructure.ChapterRepository;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Para módulos que referenciam capítulos públicos, como a biblioteca.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PublicChapters {

    private final ChapterRepository chapters;

    PublicChapters(ChapterRepository chapters) {
        this.chapters = chapters;
    }

    /**
     * Um capítulo de outra obra ou fora do ar responde como inexistente.
     */
    public Chapter requirePublished(UUID bookId, UUID chapterId) {
        return chapters.findByIdAndBookId(chapterId, bookId)
                .filter(Chapter::isPublished)
                .orElseThrow(PublicChapters::notFound);
    }

    /**
     * Para quem só tem o identificador do capítulo. Um capítulo de obra fora do ar responde como inexistente.
     */
    public Chapter requirePublic(UUID chapterId) {
        return chapters.findById(chapterId)
                .filter(chapter -> chapter.isPublished() && PublicBooks.isPublic(chapter.getBook()))
                .orElseThrow(PublicChapters::notFound);
    }

    private static ApiException notFound() {
        return ApiException.notFound("CHAPTER_NOT_FOUND", "Capítulo não encontrado.");
    }
}
