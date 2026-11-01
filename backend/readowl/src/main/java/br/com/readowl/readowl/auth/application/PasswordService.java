package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.auth.infrastructure.KnownDevices;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;

@Service
public class PasswordService {

    private final UserService users;
    private final AccountTokens tokens;
    private final AccountMails mails;
    private final KnownDevices devices;
    private final SessionManager sessions;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final TransactionOperations transaction;

    PasswordService(UserService users, AccountTokens tokens, AccountMails mails, KnownDevices devices,
                    SessionManager sessions, PasswordEncoder passwordEncoder, RateLimiter rateLimiter,
                    TransactionOperations transaction) {
        this.users = users;
        this.tokens = tokens;
        this.mails = mails;
        this.devices = devices;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.transaction = transaction;
    }

    /**
     * Responde da mesma forma para qualquer e-mail, sem revelar se a conta existe.
     */
    public void requestReset(String email, String clientIp) {
        String normalizedEmail = User.normalizeEmail(email);
        rateLimiter.acquire(RateLimitPolicy.ACCOUNT_MAIL_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.ACCOUNT_MAIL_EMAIL_IP, normalizedEmail + "|" + clientIp);
        rateLimiter.acquire(RateLimitPolicy.ACCOUNT_MAIL_EMAIL, normalizedEmail);
        transaction.executeWithoutResult(status -> users.findByEmail(normalizedEmail)
                .filter(User::isActive)
                .ifPresent(user -> mails.request(AccountMailType.RESET_PASSWORD, user.getEmail())));
    }

    /**
     * Usar o link prova a posse do e-mail, então também o confirma. Isso substitui a senha de uma conta
     * pré-cadastrada por outra pessoa, e todas as sessões da conta são encerradas. Também libera o login, que
     * um ataque pode ter bloqueado, e faz os navegadores conhecidos perderem essa condição.
     */
    public void reset(String token, String newPassword, String clientIp) {
        rateLimiter.acquire(RateLimitPolicy.TOKEN_IP, clientIp);
        String passwordHash = passwordEncoder.encode(newPassword);
        User user = transaction.execute(status -> {
            User consumed = tokens.consumePasswordReset(token);
            consumed.changePasswordHash(passwordHash);
            consumed.verifyEmail(Instant.now());
            devices.forgetAll(consumed.getId());
            return consumed;
        });
        rateLimiter.reset(RateLimitPolicy.LOGIN_ACCOUNT, user.getEmail());
        sessions.revokeAll(user.getId());
    }

    /**
     * Mantém apenas a sessão atual, com um novo ID, e encerra as demais.
     */
    public void change(SessionUser principal, String currentPassword, String newPassword,
                       HttpServletRequest request, HttpServletResponse response) {
        rateLimiter.acquire(RateLimitPolicy.PASSWORD_CONFIRMATION, principal.getName());
        User user = users.requireById(principal.id());
        if (!user.hasPassword()) {
            throw ApiException.conflict("PASSWORD_NOT_SET",
                    "Esta conta ainda não tem senha. Defina uma pela recuperação de senha.");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "A senha atual está incorreta.");
        }
        String passwordHash = passwordEncoder.encode(newPassword);
        transaction.executeWithoutResult(status -> {
            users.changePasswordHash(user.getId(), passwordHash);
            tokens.invalidatePasswordResets(user.getId());
            devices.forgetAll(user.getId());
        });
        sessions.revokeAllExceptCurrent(user.getId(), request);
        sessions.establish(principal, request, response);
    }
}
