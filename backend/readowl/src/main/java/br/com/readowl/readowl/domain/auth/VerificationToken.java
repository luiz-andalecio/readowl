package br.com.readowl.readowl.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "verification_tokens", uniqueConstraints = @UniqueConstraint(columnNames = "token"))
@Getter
@Setter
@NoArgsConstructor
public class VerificationToken {

    @EmbeddedId
    private VerificationTokenId id;

    @Column(nullable = false)
    private Instant expires;
}