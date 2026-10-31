package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;

/**
 * Cadastro e confirmação de e-mail.
 * <p>
 * A resposta do cadastro não revela se o e-mail já tem conta: quem o possui recebe um aviso. O aviso
 * aponta para a recuperação de senha em vez de confirmar a conta existente, porque ela pode ter sido
 * pré-cadastrada por outra pessoa, com uma senha que o dono do e-mail não conhece.
 */
@Service
public class RegistrationService {

    private final UserService users;
    private final AccountTokens tokens;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transaction;

    RegistrationService(UserService users, AccountTokens tokens, PasswordEncoder passwordEncoder,
                        RateLimiter rateLimiter, ApplicationEventPublisher events, TransactionOperations transaction) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.transaction = transaction;
    }

    public void register(String name, String username, String email, String password, String clientIp) {
        String normalizedEmail = User.normalizeEmail(email);
        rateLimiter.acquire(RateLimitPolicy.REGISTER_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.REGISTER_EMAIL, normalizedEmail);
        // Calculado em todos os casos: o tempo de resposta também não pode revelar se o e-mail existe.
        String passwordHash = passwordEncoder.encode(password);
        try {
            transaction.executeWithoutResult(status -> {
                // Antes do e-mail: um username ocupado recebe a mesma resposta, exista ou não o e-mail.
                if (users.isUsernameUnavailable(username)) {
                    throw usernameTaken();
                }
                if (users.findByEmail(normalizedEmail).isPresent()) {
                    events.publishEvent(new AccountMailRequested(
                            AccountMailRequested.Type.ACCOUNT_ALREADY_EXISTS, normalizedEmail, null));
                    return;
                }
                tokens.sendVerification(users.create(name, username, normalizedEmail, passwordHash));
            });
        } catch (DataIntegrityViolationException ex) {
            // Dois cadastros simultâneos: o perdedor recebe a resposta que teria se tivesse chegado
            // depois. Consulta de novo porque a mensagem do banco varia com o idioma do servidor.
            if (users.isUsernameUnavailable(username)) {
                throw usernameTaken();
            }
            if (users.findByEmail(normalizedEmail).isEmpty()) {
                throw ex;
            }
        }
    }

    private static ApiException usernameTaken() {
        return ApiException.conflict("USERNAME_TAKEN", "Este nome de usuário não está disponível.");
    }

    public void verifyEmail(String token, String clientIp) {
        rateLimiter.acquire(RateLimitPolicy.TOKEN_IP, clientIp);
        transaction.executeWithoutResult(status -> {
            User user = tokens.consumeVerification(token);
            user.verifyEmail(Instant.now());
        });
    }

    /**
     * Responde da mesma forma para qualquer e-mail; só envia se houver conta ativa ainda não confirmada.
     */
    public void resendVerification(String email, String clientIp) {
        String normalizedEmail = User.normalizeEmail(email);
        rateLimiter.acquire(RateLimitPolicy.ACCOUNT_MAIL_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.ACCOUNT_MAIL_EMAIL, normalizedEmail);
        transaction.executeWithoutResult(status -> users.findByEmail(normalizedEmail)
                .filter(user -> user.isActive() && !user.isEmailVerified())
                .ifPresent(tokens::sendVerification));
    }
}
