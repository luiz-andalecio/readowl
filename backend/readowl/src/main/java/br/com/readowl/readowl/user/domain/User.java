package br.com.readowl.readowl.user.domain;

import br.com.readowl.readowl.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    public static final String DELETED_NAME = "Usuário removido";

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String name;

    @NotBlank
    @Size(max = 30)
    @Column(nullable = false, length = 30)
    private String username;

    @Email
    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String email;

    @Size(max = 255)
    private String passwordHash;

    @Size(max = 2000)
    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    private Instant emailVerifiedAt;

    @Column(nullable = false)
    private boolean emailNewChapters;

    private Instant deletedAt;

    @Size(max = 20)
    @Column(length = 20)
    private String termsVersion;

    private Instant termsAcceptedAt;

    public User(String name, String username, String email, String passwordHash) {
        this.name = name.trim();
        this.username = normalizeUsername(username);
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isSuspended() {
        return status == UserStatus.SUSPENDED;
    }

    public boolean isDeleted() {
        return status == UserStatus.DELETED;
    }

    public boolean hasPrivilegedRole() {
        return role != Role.USER;
    }

    public boolean hasAcceptedTerms(String version) {
        return version.equals(termsVersion);
    }

    public void acceptTerms(String version, Instant at) {
        this.termsVersion = version;
        this.termsAcceptedAt = at;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public void verifyEmail(Instant at) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = at;
        }
    }

    public void changeEmailNewChapters(boolean enabled) {
        this.emailNewChapters = enabled;
    }

    public void changeEmail(String email) {
        this.email = normalizeEmail(email);
    }

    /**
     * Retira tudo o que identifica a pessoa. A linha continua, porque comentários, denúncias e a auditoria apontam para
     * ela. O sufixo aleatório libera o e-mail e o nome de usuário, sem permitir ligar a conta a eles.
     */
    public void anonymize(Instant at) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        this.name = DELETED_NAME;
        this.username = "removido-" + suffix.substring(0, 12);
        this.email = "removido-" + suffix + "@removido.invalid";
        this.passwordHash = null;
        this.description = null;
        this.role = Role.USER;
        this.status = UserStatus.DELETED;
        this.emailVerifiedAt = null;
        this.emailNewChapters = false;
        this.deletedAt = at;
    }

    public void changeUsername(String username) {
        this.username = normalizeUsername(username);
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    public void changeStatus(UserStatus status) {
        this.status = status;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * Atualiza apenas os campos informados. Uma descrição em branco é removida.
     */
    public void updateProfile(String name, String description) {
        if (name != null) {
            this.name = name.trim();
        }
        if (description != null) {
            this.description = description.isBlank() ? null : description.trim();
        }
    }
}
