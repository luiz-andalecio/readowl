package br.com.readowl.readowl.media.application;

import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Guarda imagens públicas no armazenamento. Quem usa chama {@link #fromFile} e {@link #fromLink} fora de transação, para não
 * prender uma conexão com o banco durante o download e o envio. O arquivo anterior só sai depois do commit: se algo falhar
 * antes, o registro continua apontando para um arquivo que existe. Se a limpeza falhar, sobra um arquivo sem uso, registrado no
 * log, nunca um link quebrado.
 */
@Service
public class StoredImages {

    private static final Logger log = LoggerFactory.getLogger(StoredImages.class);

    private final ObjectProvider<ObjectStorage> storage;
    private final RemoteImages remote;
    private final ExternalImages external;
    private final ReadowlProperties properties;
    private final ApplicationEventPublisher events;

    StoredImages(ObjectProvider<ObjectStorage> storage, RemoteImages remote, ExternalImages external,
                 ReadowlProperties properties, ApplicationEventPublisher events) {
        this.storage = storage;
        this.remote = remote;
        this.external = external;
        this.properties = properties;
        this.events = events;
    }

    public record Stored(String key, String url) {
    }

    record ImageFileReleased(String key) {
    }

    public Stored fromFile(StoredImageType type, UUID owner, byte[] file) {
        ObjectStorage objects = available();
        return put(objects, type, owner, file);
    }

    /**
     * Guarda uma cópia: a imagem continua no ar mesmo que saia do site de origem.
     */
    public Stored fromLink(StoredImageType type, UUID owner, String url) {
        String cleaned = url.trim();
        if (!external.isAllowed(cleaned)) {
            throw ApiException.badRequest(type.invalidUrlCode(), "Use o endereço completo da imagem, com https://, em um dos sites permitidos.");
        }
        ObjectStorage objects = available();
        return put(objects, type, owner, remote.download(cleaned));
    }

    /**
     * Apaga o arquivo depois do commit de quem chama. Sem transação, apaga na hora. Sem chave, a imagem é só um endereço
     * externo, e não há o que apagar.
     */
    public void release(String key) {
        if (key != null) {
            events.publishEvent(new ImageFileReleased(key));
        }
    }

    private ObjectStorage available() {
        ObjectStorage objects = storage.getIfAvailable();
        if (objects == null) {
            throw ApiException.serviceUnavailable("IMAGES_UNAVAILABLE", "O envio de imagens não está disponível.");
        }
        return objects;
    }

    private Stored put(ObjectStorage objects, StoredImageType type, UUID owner, byte[] file) {
        byte[] image = ImageFiles.normalize(file, type::check, type.stored());
        String key = type.folder() + "/" + owner + "/" + UUID.randomUUID() + ".jpg";
        try {
            objects.put(key, image, ImageFiles.CONTENT_TYPE);
        } catch (RuntimeException ex) {
            log.error("Falha ao enviar a imagem: tipo={}, dono={}, causa={}", type, owner, ex.getClass().getSimpleName());
            throw ApiException.serviceUnavailable("IMAGES_UNAVAILABLE", "O envio da imagem falhou. Tente novamente.");
        }
        return new Stored(key, properties.storage().publicUrl().replaceAll("/+$", "") + "/" + key);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void deleteReleasedFile(ImageFileReleased released) {
        ObjectStorage objects = storage.getIfAvailable();
        if (objects == null) {
            log.warn("Imagem sem armazenamento para apagar: chave={}", released.key());
            return;
        }
        try {
            objects.delete(released.key());
        } catch (RuntimeException ex) {
            log.error("Imagem nao apagada, sobrou um arquivo sem uso: chave={}, causa={}", released.key(), ex.getClass().getSimpleName());
        }
    }
}
