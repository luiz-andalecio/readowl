package br.com.readowl.readowl.user.application;

import br.com.readowl.readowl.media.application.AvatarImages;
import br.com.readowl.readowl.media.application.ObjectStorage;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;

import java.util.UUID;

/**
 * Foto de perfil. O envio ao armazenamento fica fora de transação, para não prender uma conexão com o banco. O arquivo
 * anterior só sai depois do commit: se algo falhar antes, a conta continua apontando para um arquivo que existe. Se a
 * limpeza falhar, sobra um arquivo sem uso, registrado no log, nunca um link quebrado.
 */
@Service
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    private final UserService users;
    private final ObjectProvider<ObjectStorage> storage;
    private final ReadowlProperties properties;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transaction;

    AvatarService(UserService users, ObjectProvider<ObjectStorage> storage, ReadowlProperties properties,
                  ApplicationEventPublisher events, TransactionOperations transaction) {
        this.users = users;
        this.storage = storage;
        this.properties = properties;
        this.events = events;
        this.transaction = transaction;
    }

    record AvatarFileReleased(String key) {
    }

    public record AvatarResponse(String avatarUrl) {
    }

    public AvatarResponse upload(UUID userId, byte[] file) {
        ObjectStorage objects = storage.getIfAvailable();
        if (objects == null) {
            throw ApiException.serviceUnavailable("AVATARS_UNAVAILABLE", "O envio de fotos não está disponível.");
        }
        byte[] image = AvatarImages.normalize(file);
        String key = "avatars/" + userId + "/" + UUID.randomUUID() + ".jpg";
        try {
            objects.put(key, image, AvatarImages.CONTENT_TYPE);
        } catch (RuntimeException ex) {
            log.error("Falha ao enviar a foto: conta={}, causa={}", userId, ex.getClass().getSimpleName());
            throw ApiException.serviceUnavailable("AVATARS_UNAVAILABLE", "O envio de fotos falhou. Tente novamente.");
        }
        String url = properties.storage().publicUrl().replaceAll("/+$", "") + "/" + key;
        try {
            transaction.executeWithoutResult(status -> release(users.changeAvatar(userId, key, url)));
        } catch (RuntimeException ex) {
            events.publishEvent(new AvatarFileReleased(key));
            throw ex;
        }
        return new AvatarResponse(url);
    }

    /**
     * Junta-se à transação de quem chama, como a moderação e a exclusão da conta.
     */
    @Transactional
    public boolean remove(UUID userId) {
        String previous = users.changeAvatar(userId, null, null);
        release(previous);
        return previous != null;
    }

    private void release(String key) {
        if (key != null) {
            events.publishEvent(new AvatarFileReleased(key));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void deleteReleasedFile(AvatarFileReleased released) {
        ObjectStorage objects = storage.getIfAvailable();
        if (objects == null) {
            log.warn("Foto sem armazenamento para apagar: chave={}", released.key());
            return;
        }
        try {
            objects.delete(released.key());
        } catch (RuntimeException ex) {
            log.error("Foto nao apagada, sobrou um arquivo sem uso: chave={}, causa={}", released.key(), ex.getClass().getSimpleName());
        }
    }
}
