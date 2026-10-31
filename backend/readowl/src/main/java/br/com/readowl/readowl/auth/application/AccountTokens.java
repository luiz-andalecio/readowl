package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.auth.domain.OneTimeToken;
import br.com.readowl.readowl.auth.domain.PasswordResetToken;
import br.com.readowl.readowl.auth.domain.VerificationToken;
import br.com.readowl.readowl.auth.infrastructure.PasswordResetTokenRepository;
import br.com.readowl.readowl.auth.infrastructure.VerificationTokenRepository;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.security.Hashing;
import br.com.readowl.readowl.user.domain.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Emite e consome os tokens enviados por e-mail. Emitir um token invalida os anteriores do mesmo tipo.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class AccountTokens {

    private static final Duration VERIFICATION_VALIDITY = Duration.ofHours(24);
    private static final Duration RESET_VALIDITY = Duration.ofHours(1);
    private static final Duration EXPIRED_RETENTION = Duration.ofDays(1);
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final VerificationTokenRepository verificationTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final ApplicationEventPublisher events;

    AccountTokens(VerificationTokenRepository verificationTokens, PasswordResetTokenRepository resetTokens,
                  ApplicationEventPublisher events) {
        this.verificationTokens = verificationTokens;
        this.resetTokens = resetTokens;
        this.events = events;
    }

    void sendVerification(User user) {
        verificationTokens.deleteUnconsumedByUserId(user.getId());
        String token = newToken();
        verificationTokens.save(new VerificationToken(user, Hashing.sha256Hex(token),
                Instant.now().plus(VERIFICATION_VALIDITY)));
        events.publishEvent(new AccountMailRequested(AccountMailRequested.Type.VERIFY_EMAIL, user.getEmail(), token));
    }

    void sendPasswordReset(User user) {
        resetTokens.deleteUnconsumedByUserId(user.getId());
        String token = newToken();
        resetTokens.save(new PasswordResetToken(user, Hashing.sha256Hex(token), Instant.now().plus(RESET_VALIDITY)));
        events.publishEvent(new AccountMailRequested(AccountMailRequested.Type.RESET_PASSWORD, user.getEmail(), token));
    }

    User consumeVerification(String token) {
        User user = consume(verificationTokens.findByTokenHash(Hashing.sha256Hex(token)));
        verificationTokens.deleteUnconsumedByUserId(user.getId());
        return user;
    }

    User consumePasswordReset(String token) {
        User user = consume(resetTokens.findByTokenHash(Hashing.sha256Hex(token)));
        resetTokens.deleteUnconsumedByUserId(user.getId());
        return user;
    }

    void invalidatePasswordResets(UUID userId) {
        resetTokens.deleteUnconsumedByUserId(userId);
    }

    /**
     * Inválido, expirado ou já usado dão a mesma resposta, sem revelar qual foi o caso.
     */
    private static User consume(Optional<? extends OneTimeToken> stored) {
        Instant now = Instant.now();
        OneTimeToken token = stored
                .filter(candidate -> candidate.isUsable(now))
                .filter(candidate -> candidate.getUser().isActive())
                .orElseThrow(() -> ApiException.badRequest("INVALID_TOKEN", "Link inválido ou expirado."));
        token.consume(now);
        return token.getUser();
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    @Transactional
    public void purgeExpired() {
        Instant limit = Instant.now().minus(EXPIRED_RETENTION);
        verificationTokens.deleteExpiredBefore(limit);
        resetTokens.deleteExpiredBefore(limit);
    }
}
