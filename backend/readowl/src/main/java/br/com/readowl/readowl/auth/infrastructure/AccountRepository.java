package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.auth.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByProviderAndProviderAccountId(String provider, String providerAccountId);

    boolean existsByUserIdAndProvider(UUID userId, String provider);
}
