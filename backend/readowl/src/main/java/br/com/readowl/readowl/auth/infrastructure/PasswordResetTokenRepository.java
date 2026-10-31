package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.auth.domain.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * O bloqueio serializa usos simultâneos do mesmo token, garantindo o uso único.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from PasswordResetToken t where t.user.id = :userId and t.consumedAt is null")
    void deleteUnconsumedByUserId(UUID userId);

    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :limit")
    int deleteExpiredBefore(Instant limit);
}
