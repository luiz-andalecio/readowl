package br.com.readowl.readowl.auth.domain;

import br.com.readowl.readowl.user.domain.User;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "verification_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationToken extends OneTimeToken {

    public VerificationToken(User user, String tokenHash, Instant expiresAt) {
        super(user, tokenHash, expiresAt);
    }
}
