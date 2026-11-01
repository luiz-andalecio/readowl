package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.publishing.infrastructure.ChapterRevisionRepository;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Os limites por quantidade não bastam: cada capítulo pode ter vários megabytes. Somar o que a conta guarda custa
 * caro para rodar a cada salvamento automático, então a conta é feita só quando o conteúdo é grande ou definitivo.
 */
@Component
class StorageQuota {

    private static final int LARGE_DRAFT_BYTES = 256 * 1024;

    private final ChapterRevisionRepository revisions;
    private final long maxBytes;

    StorageQuota(ChapterRevisionRepository revisions, ReadowlProperties properties) {
        this.revisions = revisions;
        this.maxBytes = properties.limits().storedBytesPerAccount();
    }

    /**
     * Rascunhos de tamanho normal, a grande maioria, não fazem a conta.
     */
    void requireRoomForDraft(UUID authorId, long incomingBytes) {
        if (incomingBytes >= LARGE_DRAFT_BYTES) {
            requireRoom(authorId, incomingBytes);
        }
    }

    void requireRoom(UUID authorId, long incomingBytes) {
        if (revisions.sumStoredBytesOfAuthor(authorId) + incomingBytes > maxBytes) {
            throw ApiException.conflict("STORAGE_LIMIT_REACHED", "Esta conta atingiu o limite de armazenamento.")
                    .withProperty("limit", maxBytes);
        }
    }
}
