package br.com.readowl.readowl.publishing.application;

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
                .orElseThrow(() -> ApiException.notFound("CHAPTER_NOT_FOUND", "Capítulo não encontrado."));
    }
}
