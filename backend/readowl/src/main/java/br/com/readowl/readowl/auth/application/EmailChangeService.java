package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.auth.domain.EmailChangeToken;
import br.com.readowl.readowl.auth.infrastructure.KnownDevices;
import br.com.readowl.readowl.shared.audit.SecurityEvents;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;

/**
 * Troca de e-mail: a conta confirma quem pede, e o link vai para o endereço novo, que prova a posse dele. O endereço
 * antigo recebe um aviso, para que a pessoa perceba uma invasão.
 * <p>
 * A resposta do pedido não revela se o endereço novo já tem conta: o dono dele recebe o aviso de sempre.
 */
@Service
public class EmailChangeService {

    private final UserService users;
    private final AccountTokens tokens;
    private final AccountMails mails;
    private final AccountConfirmation confirmation;
    private final KnownDevices devices;
    private final SessionManager sessions;
    private final RateLimiter rateLimiter;
    private final TransactionOperations transaction;
    private final SecurityEvents events;

    EmailChangeService(UserService users, AccountTokens tokens, AccountMails mails, AccountConfirmation confirmation,
                       KnownDevices devices, SessionManager sessions, RateLimiter rateLimiter,
                       TransactionOperations transaction, SecurityEvents events) {
        this.users = users;
        this.tokens = tokens;
        this.mails = mails;
        this.confirmation = confirmation;
        this.devices = devices;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
        this.transaction = transaction;
        this.events = events;
    }

    public void request(SessionUser principal, String password, String newEmail, String clientIp) {
        String normalized = User.normalizeEmail(newEmail);
        confirmation.require(principal, password);
        rateLimiter.acquire(RateLimitPolicy.ACCOUNT_MAIL_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.ACCOUNT_MAIL_EMAIL_IP, normalized + "|" + clientIp);
        rateLimiter.acquire(RateLimitPolicy.ACCOUNT_MAIL_EMAIL, normalized);
        transaction.executeWithoutResult(status -> {
            if (users.findByEmail(normalized).isPresent()) {
                mails.request(AccountMailType.ACCOUNT_ALREADY_EXISTS, normalized);
            } else {
                mails.requestEmailChange(normalized, principal.id());
            }
        });
        events.record(SecurityEvents.Event.EMAIL_CHANGE_REQUESTED, principal.id());
    }

    /**
     * Como numa troca de senha, encerra todas as sessões e esquece os navegadores conhecidos.
     */
    public void confirm(String token, String clientIp) {
        rateLimiter.acquire(RateLimitPolicy.TOKEN_IP, clientIp);
        User user;
        try {
            user = transaction.execute(status -> {
                EmailChangeToken consumed = tokens.consumeEmailChange(token);
                if (users.findByEmail(consumed.getNewEmail()).isPresent()) {
                    throw emailTaken();
                }
                User changed = consumed.getUser();
                String previous = changed.getEmail();
                changed.changeEmail(consumed.getNewEmail());
                changed.verifyEmail(Instant.now());
                tokens.invalidatePasswordResets(changed.getId());
                devices.forgetAll(changed.getId());
                mails.request(AccountMailType.EMAIL_CHANGED, previous);
                return changed;
            });
        } catch (DataIntegrityViolationException ex) {
            // Outra conta passou a usar o endereço ao mesmo tempo.
            throw emailTaken();
        }
        sessions.revokeAll(user.getId());
        events.record(SecurityEvents.Event.EMAIL_CHANGED, user.getId());
    }

    private static ApiException emailTaken() {
        return ApiException.conflict("EMAIL_TAKEN", "Este e-mail já está em uso.");
    }
}
