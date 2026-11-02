package br.com.readowl.readowl.user.application;

import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.text.Slugs;
import br.com.readowl.readowl.user.domain.Role;
import br.com.readowl.readowl.user.domain.User;
import br.com.readowl.readowl.user.domain.UserStatus;
import br.com.readowl.readowl.user.infrastructure.UserLinks;
import br.com.readowl.readowl.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class UserService {

    /**
     * Poderiam se passar pela plataforma. "me", da rota /users/me, já é recusado pelo tamanho mínimo.
     */
    private static final Set<String> RESERVED_USERNAMES = Set.of(
            "admin", "administrador", "administrator", "moderador", "moderator", "mod", "readowl", "root", "suporte",
            "support", "sistema", "system", "equipe", "staff", "team", "oficial", "official", "contato", "contact",
            "ajuda", "help", "seguranca", "security");
    private static final int GENERATED_BASE_LENGTH = 20;

    private final UserRepository users;
    private final ReadowlProperties properties;
    private final UserLinks links;

    public UserService(UserRepository users, ReadowlProperties properties, UserLinks links) {
        this.users = users;
        this.properties = properties;
        this.links = links;
    }

    public record CurrentTerms(String version, int minimumAge) {
    }

    public CurrentTerms currentTerms() {
        return new CurrentTerms(properties.terms().version(), properties.terms().minimumAge());
    }

    public void requireCurrentTerms(String version) {
        if (!properties.terms().version().equals(version)) {
            throw ApiException.conflict("TERMS_OUTDATED", "Os termos de uso mudaram. Leia a versão atual.")
                    .withProperty("currentVersion", properties.terms().version());
        }
    }

    public boolean hasAcceptedCurrentTerms(UUID id) {
        return users.existsByIdAndTermsVersion(id, properties.terms().version());
    }

    @Transactional
    public void acceptTerms(UUID id, String version) {
        requireCurrentTerms(version);
        requireById(id).acceptTerms(version, Instant.now());
    }

    /**
     * Sem root ativo, ninguém mais conseguiria administrar contas pela aplicação. O bloqueio impede que
     * dois roots removam um ao outro ao mesmo tempo.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAnotherActiveRoot(UUID id) {
        long others = users.findLockedByRoleAndStatus(Role.ROOT, UserStatus.ACTIVE).stream()
                .filter(root -> !root.getId().equals(id))
                .count();
        if (others == 0) {
            throw ApiException.conflict("LAST_ROOT", "Esta é a última conta root ativa.");
        }
    }

    /**
     * A última conta root não sai: restaria uma plataforma sem administração.
     *
     * @return o e-mail que a conta tinha
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String anonymize(UUID id, Instant at) {
        User user = requireById(id);
        if (user.getRole() == Role.ROOT && user.isActive()) {
            requireAnotherActiveRoot(id);
        }
        String email = user.getEmail();
        user.anonymize(at);
        users.saveAndFlush(user);
        return email;
    }

    public record PublicProfile(String username, String name, String description, String avatarUrl,
                                List<UserLinks.ExternalLink> links, Instant createdAt) {

        static PublicProfile from(User user, List<UserLinks.ExternalLink> links) {
            return new PublicProfile(user.getUsername(), user.getName(), user.getDescription(), user.getAvatarUrl(), links,
                    user.getCreatedAt());
        }
    }

    public record MyProfile(UUID id, String username, String name, String email, String description, String avatarUrl,
                            List<UserLinks.ExternalLink> links, Role role, boolean emailVerified, boolean emailNewChapters,
                            Instant createdAt) {

        static MyProfile from(User user, List<UserLinks.ExternalLink> links) {
            return new MyProfile(user.getId(), user.getUsername(), user.getName(), user.getEmail(),
                    user.getDescription(), user.getAvatarUrl(), links, user.getRole(), user.isEmailVerified(),
                    user.isEmailNewChapters(), user.getCreatedAt());
        }
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

    /**
     * A conta continua ativa e com o mesmo papel que a sessão guarda.
     */
    public boolean hasAccess(UUID id, Role role) {
        return users.existsByIdAndRoleAndStatus(id, role, UserStatus.ACTIVE);
    }

    public PublicProfile publicProfile(String username) {
        User user = requirePublicProfile(username);
        return PublicProfile.from(user, links.of(user.getId()));
    }

    public MyProfile myProfile(UUID id) {
        return MyProfile.from(requireById(id), links.of(id));
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

    /**
     * Campos nulos não são alterados.
     */
    @Transactional
    public MyProfile updateProfile(UUID id, String name, String description, Boolean emailNewChapters) {
        User user = requireById(id);
        user.updateProfile(name, description);
        if (emailNewChapters != null) {
            user.changeEmailNewChapters(emailNewChapters);
        }
        return MyProfile.from(user, links.of(id));
    }

    public List<UserLinks.ExternalLink> linksOf(UUID id) {
        return links.of(id);
    }

    /**
     * A pessoa envia a lista inteira, e a ordem dela é a de exibição.
     */
    @Transactional
    public List<UserLinks.ExternalLink> replaceLinks(UUID id, List<UserLinks.ExternalLink> newLinks) {
        List<UserLinks.ExternalLink> cleaned = newLinks.stream()
                .map(link -> new UserLinks.ExternalLink(link.type(), link.url().trim())).distinct().toList();
        for (UserLinks.ExternalLink link : cleaned) {
            if (!link.type().accepts(link.url())) {
                throw ApiException.badRequest("INVALID_LINK", "O endereço não corresponde a " + link.type() + ". Use um endereço completo com https://.");
            }
        }
        requireById(id);
        links.replace(id, cleaned);
        return cleaned;
    }

    /**
     * @return a chave do arquivo anterior, para apagar depois do commit
     */
    @Transactional
    public String changeAvatar(UUID id, String key, String url) {
        User user = requireById(id);
        String previous = user.getAvatarKey();
        user.changeAvatar(key, url);
        return previous;
    }

    public Map<UUID, String> usernamesById(Collection<UUID> ids) {
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, User::getUsername));
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
        String slug = Slugs.slugify(name);
        if (slug.length() < 3) {
            return "leitor";
        }
        return slug.substring(0, Math.min(slug.length(), GENERATED_BASE_LENGTH));
    }
}
