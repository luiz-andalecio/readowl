package br.com.readowl.readowl.auth.domain;

import br.com.readowl.readowl.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "email_change_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailChangeToken extends OneTimeToken {

    @Column(nullable = false)
    private String newEmail;

    public EmailChangeToken(User user, String tokenHash, String newEmail, Instant expiresAt) {
        super(user, tokenHash, expiresAt);
        this.newEmail = newEmail;
    }
}
