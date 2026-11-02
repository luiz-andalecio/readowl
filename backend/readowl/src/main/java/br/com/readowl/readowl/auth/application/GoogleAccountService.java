package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.auth.domain.Account;
import br.com.readowl.readowl.auth.infrastructure.AccountRepository;
import br.com.readowl.readowl.media.application.ExternalImages;
import br.com.readowl.readowl.shared.audit.SecurityEvents;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.User;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A identidade do Google é localizada pelo "sub", que não muda, e não pelo e-mail. Um e-mail igual a
 * uma conta existente nunca vincula automaticamente: isso permitiria sequestrar a conta por pré-cadastro.
 */
@Service
public class GoogleAccountService {

    static final String PROVIDER = "google";
    private static final String DEFAULT_NAME = "Leitor Readowl";
    private static final int MAX_NAME_LENGTH = 100;

    private final UserService users;
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final ObjectProvider<ClientRegistrationRepository> googleRegistration;
    private final AccountMails mails;
    private final SecurityEvents events;
    private final ExternalImages images;

    GoogleAccountService(UserService users, AccountRepository accounts, PasswordEncoder passwordEncoder,
                         RateLimiter rateLimiter, ObjectProvider<ClientRegistrationRepository> googleRegistration,
                         AccountMails mails, SecurityEvents events, ExternalImages images) {
        this.images = images;
        this.mails = mails;
        this.events = events;
        this.users = users;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.googleRegistration = googleRegistration;
    }

    @Transactional
    public SessionUser login(GoogleIdentity identity) {
        Optional<Account> linked = accounts.findByProviderAndProviderAccountId(PROVIDER, identity.subject());
        if (linked.isPresent()) {
            refreshPicture(linked.get().getUser(), identity);
            SessionUser user = activeSessionUser(linked.get().getUser());
            events.record(SecurityEvents.Event.GOOGLE_LOGIN_SUCCEEDED, user.id());
            return user;
        }
        if (!identity.emailVerified()) {
            throw ApiException.forbidden("GOOGLE_EMAIL_UNVERIFIED", "O Google não confirmou este e-mail.");
        }
        if (users.findByEmail(identity.email()).isPresent()) {
            throw ApiException.conflict("GOOGLE_ACCOUNT_EXISTS",
                    "Já existe uma conta com este e-mail. Entre com a senha e vincule o Google.");
        }
        User user = users.createVerified(displayName(identity), identity.email(), Instant.now());
        usePicture(user, identity);
        accounts.saveAndFlush(new Account(user, PROVIDER, identity.subject()));
        events.record(SecurityEvents.Event.GOOGLE_LOGIN_SUCCEEDED, user.getId());
        return activeSessionUser(user);
    }

    /**
     * Confirma quem pede o vínculo antes de enviar o navegador ao Google. Sem transação, para que uma
     * tentativa com a senha errada continue contando no limite.
     */
    public void authorizeLink(SessionUser principal, String password) {
        if (googleRegistration.getIfAvailable() == null) {
            throw ApiException.serviceUnavailable("GOOGLE_UNAVAILABLE", "O login com Google não está disponível.");
        }
        rateLimiter.acquire(RateLimitPolicy.PASSWORD_CONFIRMATION, principal.getName());
        User user = users.requireById(principal.id());
        if (accounts.existsByUserIdAndProvider(user.getId(), PROVIDER)) {
            throw ApiException.conflict("GOOGLE_ALREADY_LINKED", "Esta conta já está vinculada ao Google.");
        }
        if (user.hasPassword() && (password == null || !passwordEncoder.matches(password, user.getPasswordHash()))) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "A senha atual está incorreta.");
        }
    }

    @Transactional
    public SessionUser link(UUID userId, GoogleIdentity identity) {
        User user = users.requireById(userId);
        Optional<Account> linked = accounts.findByProviderAndProviderAccountId(PROVIDER, identity.subject());
        if (linked.isPresent() && !linked.get().getUser().getId().equals(userId)) {
            throw ApiException.conflict("GOOGLE_LINKED_TO_OTHER_ACCOUNT",
                    "Esta conta do Google já está vinculada a outro usuário.");
        }
        if (linked.isEmpty()) {
            if (accounts.existsByUserIdAndProvider(userId, PROVIDER)) {
                throw ApiException.conflict("GOOGLE_ALREADY_LINKED", "Esta conta já está vinculada ao Google.");
            }
            accounts.saveAndFlush(new Account(user, PROVIDER, identity.subject()));
            if (user.getAvatarUrl() == null) {
                usePicture(user, identity);
            }
            mails.request(AccountMailType.GOOGLE_LINKED, user.getEmail());
            events.record(SecurityEvents.Event.GOOGLE_LINKED, userId);
        }
        return activeSessionUser(user);
    }

    @Transactional(readOnly = true)
    public SessionUser sessionUser(UUID userId) {
        return activeSessionUser(users.requireById(userId));
    }

    /**
     * Acompanha a foto do Google só de quem a está usando. Uma foto enviada ou hospedada vale mais, e quem removeu a foto, ou
     * teve a foto removida pela moderação, não a recebe de volta no login seguinte.
     */
    private void refreshPicture(User user, GoogleIdentity identity) {
        if (user.isAvatarFromLogin()) {
            usePicture(user, identity);
        }
    }

    private void usePicture(User user, GoogleIdentity identity) {
        if (identity.picture() != null && images.isAllowed(identity.picture())) {
            user.useLoginPicture(identity.picture());
        }
    }

    private static SessionUser activeSessionUser(User user) {
        if (!user.isActive()) {
            throw ApiException.forbidden("ACCOUNT_SUSPENDED", "Esta conta está suspensa.");
        }
        return new SessionUser(user.getId(), user.getRole());
    }

    private static String displayName(GoogleIdentity identity) {
        String name = identity.name();
        if (name == null || name.isBlank()) {
            return DEFAULT_NAME;
        }
        String trimmed = name.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), MAX_NAME_LENGTH));
    }
}
