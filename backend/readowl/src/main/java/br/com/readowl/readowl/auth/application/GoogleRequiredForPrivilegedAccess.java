package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.auth.infrastructure.AccountRepository;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.application.PrivilegedAccessPolicy;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Essas contas só entram pelo Google, que cuida do segundo fator. Sem o vínculo, o novo papel a deixaria sem como entrar.
 */
@Component
class GoogleRequiredForPrivilegedAccess implements PrivilegedAccessPolicy {

    private final AccountRepository accounts;
    private final ReadowlProperties properties;

    GoogleRequiredForPrivilegedAccess(AccountRepository accounts, ReadowlProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    @Override
    public void requireEligible(UUID userId) {
        if (properties.privilegedGoogleLoginRequired() && !accounts.existsByUserIdAndProvider(userId, GoogleAccountService.PROVIDER)) {
            throw ApiException.conflict("GOOGLE_REQUIRED_FOR_ROLE", "Vincule uma conta do Google antes de receber este papel.");
        }
    }
}
