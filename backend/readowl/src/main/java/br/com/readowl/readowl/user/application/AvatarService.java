package br.com.readowl.readowl.user.application;

import br.com.readowl.readowl.media.application.AvatarImages;
import br.com.readowl.readowl.media.application.ExternalImages;
import br.com.readowl.readowl.media.application.ObjectStorage;
import br.com.readowl.readowl.media.application.StoredImages;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final StoredImages files;
    private final TransactionOperations transaction;
    private final ExternalImages images;

    AvatarService(UserService users, ObjectProvider<ObjectStorage> storage, ReadowlProperties properties,
                  StoredImages files, TransactionOperations transaction, ExternalImages images) {
        this.images = images;
        this.users = users;
        this.storage = storage;
        this.properties = properties;
        this.files = files;
        this.transaction = transaction;
    }

    public record AvatarResponse(String avatarUrl) {
    }

    public AvatarResponse upload(UUID userId, byte[] file) {
        byte[] image = AvatarImages.normalize(file);
        ObjectStorage objects = storage.getIfAvailable();
        if (objects == null) {
            throw ApiException.serviceUnavailable("AVATARS_UNAVAILABLE", "O envio de fotos não está disponível.");
        }
        String key = "avatars/" + userId + "/" + UUID.randomUUID() + ".jpg";
        try {
            objects.put(key, image, AvatarImages.CONTENT_TYPE);
        } catch (RuntimeException ex) {
            log.error("Falha ao enviar a foto: conta={}, causa={}", userId, ex.getClass().getSimpleName());
            throw ApiException.serviceUnavailable("AVATARS_UNAVAILABLE", "O envio de fotos falhou. Tente novamente.");
        }
        String url = properties.storage().publicUrl().replaceAll("/+$", "") + "/" + key;
        try {
            transaction.executeWithoutResult(status -> files.release(users.changeAvatar(userId, key, url)));
        } catch (RuntimeException ex) {
            files.release(key);
            throw ex;
        }
        return new AvatarResponse(url);
    }

    /**
     * Foto hospedada em um dos sites permitidos. Substitui a enviada, cujo arquivo é apagado, e a do login, que não volta
     * no login seguinte.
     */
    @Transactional
    public AvatarResponse useHostedImage(UUID userId, String url) {
        String cleaned = url.trim();
        if (!images.isAllowed(cleaned)) {
            throw ApiException.badRequest("INVALID_AVATAR_URL", "Use o endereço completo da imagem, com https://, em um dos sites permitidos.");
        }
        files.release(users.changeAvatar(userId, null, cleaned));
        return new AvatarResponse(cleaned);
    }

    /**
     * Junta-se à transação de quem chama, como a moderação e a exclusão da conta. Uma foto vinda do login não tem arquivo aqui.
     *
     * @return se a conta tinha foto
     */
    @Transactional
    public boolean remove(UUID userId) {
        boolean hadPicture = users.requireById(userId).getAvatarUrl() != null;
        files.release(users.changeAvatar(userId, null, null));
        return hadPicture;
    }
}
