package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.shared.security.RecentLogin;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Confirma quem pede uma ação sensível. Quem tem senha a informa. Quem só entra pelo Google precisa ter entrado há pouco.
 * Sem transação, para que uma tentativa errada continue contando no limite.
 */
@Component
public class AccountConfirmation {

    private final UserService users;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final RecentLogin recentLogin;

    AccountConfirmation(UserService users, PasswordEncoder passwordEncoder, RateLimiter rateLimiter, RecentLogin recentLogin) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.recentLogin = recentLogin;
    }

    public void require(SessionUser principal, String password) {
        rateLimiter.acquire(RateLimitPolicy.PASSWORD_CONFIRMATION, principal.getName());
        User user = users.requireById(principal.id());
        if (!user.hasPassword()) {
            recentLogin.require();
        } else if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "A senha atual está incorreta.");
        }
    }
}
