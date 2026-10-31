package br.com.readowl.readowl.user.application;

import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.domain.User;
import br.com.readowl.readowl.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserService {

    /**
     * Poderiam se passar pela plataforma. "me", da rota /users/me, já é recusado pelo tamanho mínimo.
     */
    private static final Set<String> RESERVED_USERNAMES =
            Set.of("admin", "administrador", "moderador", "readowl", "suporte");
    private static final int GENERATED_BASE_LENGTH = 20;

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    public Optional<User> findByEmail(String email) {
        return users.findByNormalizedEmail(User.normalizeEmail(email));
    }

    public Optional<User> findById(UUID id) {
        return users.findById(id);
    }

    public User requireById(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "Usuário não encontrado."));
    }

    /**
     * Perfis só são públicos depois de o e-mail ser confirmado, e nunca durante uma suspensão.
     */
    public User requirePublicProfile(String username) {
        return users.findByUsername(User.normalizeUsername(username))
                .filter(user -> user.isActive() && user.isEmailVerified())
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "Usuário não encontrado."));
    }

    public boolean isUsernameUnavailable(String username) {
        String normalized = User.normalizeUsername(username);
        return RESERVED_USERNAMES.contains(normalized) || users.existsByUsername(normalized);
    }

    @Transactional
    public User create(String name, String username, String email, String passwordHash) {
        return users.saveAndFlush(new User(name, username, email, passwordHash));
    }

    /**
     * Conta criada pelo login com Google, que já confirmou o e-mail. O username vem do nome, e não
     * do e-mail, porque é público.
     */
    @Transactional
    public User createVerified(String name, String email, Instant verifiedAt) {
        User user = new User(name, generateUsername(name), email, null);
        user.verifyEmail(verifiedAt);
        return users.saveAndFlush(user);
    }

    @Transactional
    public User updateProfile(UUID id, String name, String description) {
        User user = requireById(id);
        user.updateProfile(name, description);
        return user;
    }

    @Transactional
    public void changePasswordHash(UUID id, String passwordHash) {
        requireById(id).changePasswordHash(passwordHash);
    }

    private String generateUsername(String name) {
        String base = slug(name);
        String candidate;
        do {
            candidate = base + "-" + UUID.randomUUID().toString().substring(0, 6);
        } while (users.existsByUsername(candidate));
        return candidate;
    }

    private static String slug(String name) {
        String slug = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() < 3) {
            return "leitor";
        }
        return slug.substring(0, Math.min(slug.length(), GENERATED_BASE_LENGTH));
    }
}
