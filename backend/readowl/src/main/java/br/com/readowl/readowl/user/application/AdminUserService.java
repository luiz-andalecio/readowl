package br.com.readowl.readowl.user.application;

import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.audit.AuditContext;
import br.com.readowl.readowl.shared.audit.AuditTrail;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.security.RecentLogin;
import br.com.readowl.readowl.shared.text.Texts;
import br.com.readowl.readowl.user.domain.Role;
import br.com.readowl.readowl.user.domain.User;
import br.com.readowl.readowl.user.domain.UserStatus;
import br.com.readowl.readowl.user.infrastructure.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Administração de contas. Senha e e-mail ficam de fora: a senha só muda pelo próprio usuário ou pela
 * recuperação, e um e-mail novo precisaria ser confirmado pelo dono.
 */
@Service
@Transactional
public class AdminUserService {

    private final UserRepository users;
    private final UserService userService;
    private final ApplicationEventPublisher events;
    private final AuditTrail audit;
    private final RecentLogin recentLogin;
    private final PrivilegedAccessPolicy privilegedAccess;

    AdminUserService(UserRepository users, UserService userService, ApplicationEventPublisher events, AuditTrail audit,
                     RecentLogin recentLogin, PrivilegedAccessPolicy privilegedAccess) {
        this.recentLogin = recentLogin;
        this.privilegedAccess = privilegedAccess;
        this.users = users;
        this.userService = userService;
        this.events = events;
        this.audit = audit;
    }

    /**
     * Campos nulos não são alterados.
     */
    public record UserChanges(String name, String username, String description, Role role, UserStatus status) {
    }

    public record AdminUserView(UUID id, String username, String name, String email, String description, Role role,
                                UserStatus status, boolean emailVerified, Instant createdAt, Instant updatedAt) {

        static AdminUserView from(User user) {
            return new AdminUserView(user.getId(), user.getUsername(), user.getName(), user.getEmail(),
                    user.getDescription(), user.getRole(), user.getStatus(), user.isEmailVerified(),
                    user.getCreatedAt(), user.getUpdatedAt());
        }
    }

    /**
     * Mostra e-mails de outras pessoas, então a leitura fica na auditoria.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserView> list(String query, Pageable pageable) {
        AuditContext.markSensitiveRead();
        String term = Texts.blankToNull(query);
        return PageResponse.from((term == null ? users.findAll(pageable)
                : users.search(Texts.escapeLike(term.trim().toLowerCase(Locale.ROOT)) + "%", pageable)).map(AdminUserView::from));
    }

    @Transactional(readOnly = true)
    public AdminUserView get(UUID id) {
        AuditContext.markSensitiveRead();
        return AdminUserView.from(userService.requireById(id));
    }

    public AdminUserView update(UUID id, UserChanges changes, SessionUser actor) {
        User user = userService.requireById(id);
        if (user.isDeleted()) {
            throw ApiException.conflict("ACCOUNT_DELETED", "Esta conta foi excluída.");
        }
        if (changes.status() == UserStatus.DELETED) {
            throw ApiException.badRequest("INVALID_STATUS", "A exclusão de uma conta só pode ser feita pela própria pessoa.");
        }
        Map<String, Object> audited = auditedChanges(user, changes);
        boolean loses = (changes.role() != null && changes.role() != Role.ROOT)
                || changes.status() == UserStatus.SUSPENDED;
        if (user.getRole() == Role.ROOT && user.isActive() && loses) {
            userService.requireAnotherActiveRoot(id);
        }
        boolean accessChanged = (changes.role() != null && changes.role() != user.getRole())
                || (changes.status() != null && changes.status() != user.getStatus());
        if (accessChanged) {
            recentLogin.require();
        }
        if (changes.role() != null && changes.role() != Role.USER && changes.role() != user.getRole()) {
            privilegedAccess.requireEligible(id);
        }

        if (changes.username() != null && !User.normalizeUsername(changes.username()).equals(user.getUsername())) {
            if (userService.isUsernameUnavailable(changes.username())) {
                throw ApiException.conflict("USERNAME_TAKEN", "Este nome de usuário não está disponível.");
            }
            user.changeUsername(changes.username());
        }
        user.updateProfile(changes.name(), changes.description());
        if (changes.role() != null) {
            user.changeRole(changes.role());
        }
        if (changes.status() != null) {
            user.changeStatus(changes.status());
        }
        User saved = users.saveAndFlush(user);
        audit.record(actor.id(), "USER_UPDATED", "users/" + id, audited);
        if (accessChanged) {
            events.publishEvent(new UserAccessChanged(id));
        }
        return AdminUserView.from(saved);
    }

    /**
     * Os valores de antes e depois, sem a descrição, que é texto livre: registra só que mudou.
     */
    private static Map<String, Object> auditedChanges(User user, UserChanges changes) {
        Map<String, Object> audited = new LinkedHashMap<>();
        addChange(audited, "name", user.getName(), changes.name());
        addChange(audited, "username", user.getUsername(), changes.username());
        addChange(audited, "role", user.getRole(), changes.role());
        addChange(audited, "status", user.getStatus(), changes.status());
        if (changes.description() != null && !changes.description().equals(user.getDescription())) {
            audited.put("description", "alterada");
        }
        return audited;
    }

    private static void addChange(Map<String, Object> audited, String field, Object before, Object after) {
        if (after != null && !after.equals(before)) {
            audited.put(field, Map.of("from", before, "to", after));
        }
    }
}
