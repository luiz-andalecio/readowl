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

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

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
