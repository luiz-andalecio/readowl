package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.auth.infrastructure.AccountRepository;
import br.com.readowl.readowl.auth.infrastructure.KnownDevices;
import br.com.readowl.readowl.shared.audit.SecurityEvents;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class LoginService {

    private final UserService users;
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final KnownDevices devices;
    private final String decoyHash;
    private final SecurityEvents events;
    private final ReadowlProperties properties;

    LoginService(UserService users, AccountRepository accounts, PasswordEncoder passwordEncoder,
                 RateLimiter rateLimiter, KnownDevices devices, SecurityEvents events, ReadowlProperties properties) {
        this.events = events;
        this.properties = properties;
        this.users = users;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.devices = devices;
        this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * @param newDeviceToken token para guardar no navegador, quando ele ainda não era conhecido
     */
    public record LoginResult(SessionUser user, String newDeviceToken) {

        // O token não vai para logs.
        @Override
        public String toString() {
            return "LoginResult[user=" + user + "]";
        }
    }

    /**
     * Conta inexistente e senha errada dão a mesma resposta e custam o mesmo tempo. Os motivos de
     * recusa específicos só aparecem para quem provou conhecer a senha.
     * <p>
     * O login certo zera as tentativas, e cada IP só gasta uma parte do limite da conta: errar a senha de outra
     * pessoa não a impede de entrar. Um navegador conhecido nem usa esse limite, então nem um ataque de muitos IPs
     * bloqueia quem já entrou dele antes.
     *
     * @param deviceToken valor do cookie do navegador, se houver
     */
    public LoginResult authenticate(String email, String password, String clientIp, String deviceToken) {
        String account = User.normalizeEmail(email);
        String accountAtIp = account + "|" + clientIp;
        rateLimiter.acquire(RateLimitPolicy.LOGIN_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.LOGIN_ACCOUNT_IP, accountAtIp);

        Optional<User> found = users.findByEmail(email).filter(User::hasPassword);
        boolean knownDevice = deviceToken != null && found.isPresent() && devices.isKnown(deviceToken, found.get().getId());
        if (!knownDevice) {
            rateLimiter.acquire(RateLimitPolicy.LOGIN_ACCOUNT, account);
        }

        boolean matches = passwordEncoder.matches(password, found.map(User::getPasswordHash).orElse(decoyHash));
        if (found.isEmpty() || !matches) {
            events.recordFailure(SecurityEvents.Event.LOGIN_FAILED, account);
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "E-mail ou senha incorretos.");
        }
        rateLimiter.reset(RateLimitPolicy.LOGIN_ACCOUNT_IP, accountAtIp);
        rateLimiter.reset(RateLimitPolicy.LOGIN_ACCOUNT, account);

        User user = found.get();
        if (!user.isActive()) {
            throw ApiException.forbidden("ACCOUNT_SUSPENDED", "Esta conta está suspensa.");
        }
        if (!user.isEmailVerified()) {
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED", "Confirme seu e-mail antes de entrar.");
        }
        if (user.hasPrivilegedRole() && properties.privilegedGoogleLoginRequired()) {
            throw ApiException.forbidden("GOOGLE_LOGIN_REQUIRED", "Esta conta só entra pelo Google.");
        }
        events.record(SecurityEvents.Event.LOGIN_SUCCEEDED, user.getId());
        return new LoginResult(new SessionUser(user.getId(), user.getRole()),
                knownDevice ? null : devices.register(user.getId()));
    }

    public SessionResponse describe(SessionUser principal) {
        if (principal == null) {
            return SessionResponse.anonymous();
        }
        return users.findById(principal.id())
                .filter(User::isActive)
                .map(user -> new SessionResponse(true, new SessionResponse.User(
                        user.getId(), user.getUsername(), user.getName(), user.getRole(), user.hasPassword(),
                        accounts.existsByUserIdAndProvider(user.getId(), GoogleAccountService.PROVIDER),
                        user.hasAcceptedTerms(properties.terms().version()), user.getAvatarUrl())))
                .orElseGet(SessionResponse::anonymous);
    }
}
