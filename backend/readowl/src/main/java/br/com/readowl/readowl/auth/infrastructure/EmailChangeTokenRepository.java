package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.auth.domain.EmailChangeToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailChangeTokenRepository extends JpaRepository<EmailChangeToken, UUID> {

    /**
     * O bloqueio serializa usos simultâneos do mesmo token, garantindo o uso único.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailChangeToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from EmailChangeToken t where t.user.id = :userId and t.consumedAt is null")
    void deleteUnconsumedByUserId(UUID userId);

    @Modifying
    @Query("delete from EmailChangeToken t where t.expiresAt < :limit")
    int deleteExpiredBefore(Instant limit);
}
