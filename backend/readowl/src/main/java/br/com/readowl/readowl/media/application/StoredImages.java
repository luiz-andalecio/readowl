package br.com.readowl.readowl.media.application;

import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Guarda imagens públicas no armazenamento. Quem usa chama {@link #fromFile} e {@link #fromLink} fora de transação, para não
 * prender uma conexão com o banco durante o download e o envio. O arquivo anterior só sai depois do commit: se algo falhar
 * antes, o registro continua apontando para um arquivo que existe. Se a limpeza falhar, sobra um arquivo sem uso, registrado no
 * log, nunca um link quebrado. Um arquivo que é prova em uma denúncia aberta fica guardado até ela ser encerrada.
 */
@Service
public class StoredImages {

    private static final Logger log = LoggerFactory.getLogger(StoredImages.class);

    private final ObjectProvider<ObjectStorage> storage;
    private final RemoteImages remote;
    private final ExternalImages external;
    private final ReadowlProperties properties;
    private final ApplicationEventPublisher events;
    private final ImageHolds holds;
    private final JdbcTemplate jdbc;

    StoredImages(ObjectProvider<ObjectStorage> storage, RemoteImages remote, ExternalImages external,
                 ReadowlProperties properties, ApplicationEventPublisher events, ImageHolds holds, JdbcTemplate jdbc) {
        this.holds = holds;
        this.jdbc = jdbc;
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
        byte[] image = ImageFiles.normalize(file, type::check, type.stored());
        return put(available(), type, owner, image);
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
        byte[] image = ImageFiles.normalize(remote.download(cleaned), type::check, type.stored());
        return put(objects, type, owner, image);
    }

    /**
     * Apaga o arquivo depois do commit de quem chama. Sem transação, apaga na hora. Sem chave, a imagem é só um endereço
     * externo, e não há o que apagar. Se o arquivo for prova em uma denúncia aberta, fica registrado para sair depois.
     */
    public void release(String key) {
        if (key == null) {
            return;
        }
        if (holds.isHeld(key)) {
            jdbc.update("INSERT INTO held_image_files (file_key) VALUES (?) ON CONFLICT DO NOTHING", key);
            return;
        }
        events.publishEvent(new ImageFileReleased(key));
    }

    /**
     * Apaga os arquivos guardados que deixaram de ser prova. Chamado ao encerrar uma denúncia, e também pela tarefa de retenção,
     * porque duas denúncias encerradas ao mesmo tempo não enxergam o encerramento uma da outra.
     *
     * @return quantos arquivos saíram
     */
    public int releaseFilesNoLongerHeld() {
        int released = 0;
        for (String key : jdbc.queryForList("SELECT file_key FROM held_image_files", String.class)) {
            if (!holds.isHeld(key) && jdbc.update("DELETE FROM held_image_files WHERE file_key = ?", key) == 1) {
                events.publishEvent(new ImageFileReleased(key));
                released++;
            }
        }
        return released;
    }

    private ObjectStorage available() {
        ObjectStorage objects = storage.getIfAvailable();
        if (objects == null) {
            throw ApiException.serviceUnavailable("IMAGES_UNAVAILABLE", "O envio de imagens não está disponível.");
        }
        return objects;
    }

    private Stored put(ObjectStorage objects, StoredImageType type, UUID owner, byte[] image) {
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
