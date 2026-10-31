package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.auth.infrastructure.AccountRepository;
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
    private final String decoyHash;

    LoginService(UserService users, AccountRepository accounts, PasswordEncoder passwordEncoder,
                 RateLimiter rateLimiter) {
        this.users = users;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Conta inexistente e senha errada dão a mesma resposta e custam o mesmo tempo. Os motivos de
     * recusa específicos só aparecem para quem provou conhecer a senha.
     */
    public SessionUser authenticate(String email, String password, String clientIp) {
        rateLimiter.acquire(RateLimitPolicy.LOGIN_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.LOGIN_ACCOUNT, User.normalizeEmail(email));

        Optional<User> found = users.findByEmail(email).filter(User::hasPassword);
        boolean matches = passwordEncoder.matches(password, found.map(User::getPasswordHash).orElse(decoyHash));
        if (found.isEmpty() || !matches) {
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "E-mail ou senha incorretos.");
        }

        User user = found.get();
        if (!user.isActive()) {
            throw ApiException.forbidden("ACCOUNT_SUSPENDED", "Esta conta está suspensa.");
        }
        if (!user.isEmailVerified()) {
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED", "Confirme seu e-mail antes de entrar.");
        }
        return new SessionUser(user.getId(), user.getRole());
    }

    public SessionResponse describe(SessionUser principal) {
        if (principal == null) {
            return SessionResponse.anonymous();
        }
        return users.findById(principal.id())
                .filter(User::isActive)
                .map(user -> new SessionResponse(true, new SessionResponse.User(
                        user.getId(), user.getUsername(), user.getName(), user.getRole(), user.hasPassword(),
                        accounts.existsByUserIdAndProvider(user.getId(), GoogleAccountService.PROVIDER))))
                .orElseGet(SessionResponse::anonymous);
    }
}
