package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.auth.application.GoogleAccountService;
import br.com.readowl.readowl.auth.application.GoogleIdentity;
import br.com.readowl.readowl.auth.application.GoogleLinkIntent;
import br.com.readowl.readowl.auth.application.SessionManager;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Conclui o retorno do Google. A autenticação criada pelo Spring Security carrega o id_token e é
 * substituída pela da sessão própria, de modo que nenhum token do Google fica gravado.
 */
class GoogleLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final String HOME_PAGE = "/";
    private static final String LOGIN_PAGE = "/login";
    private static final String ACCOUNT_PAGE = "/conta";
    private static final String GENERIC_ERROR = "GOOGLE_LOGIN_FAILED";

    private final GoogleAccountService google;
    private final SessionManager sessions;
    private final ReadowlProperties properties;

    GoogleLoginHandler(GoogleAccountService google, SessionManager sessions, ReadowlProperties properties) {
        this.google = google;
        this.sessions = sessions;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        GoogleIdentity identity = new GoogleIdentity(oidcUser.getSubject(), oidcUser.getEmail(),
                Boolean.TRUE.equals(oidcUser.getEmailVerified()), oidcUser.getFullName(), oidcUser.getPicture());
        Optional<UUID> linkingUser = GoogleLinkIntent.take(request);

        if (linkingUser.isPresent()) {
            finishLink(linkingUser.get(), identity, request, response);
        } else {
            finishLogin(identity, request, response);
        }
    }

    private void finishLogin(GoogleIdentity identity, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            sessions.establish(google.login(identity), request, response);
            response.sendRedirect(properties.pageUrl(HOME_PAGE));
        } catch (ApiException | DataIntegrityViolationException ex) {
            sessions.terminate(request, response);
            redirectWithError(LOGIN_PAGE, errorCode(ex), response);
        }
    }

    /**
     * Mesmo com o vínculo recusado, quem o pediu continua autenticado na própria conta.
     */
    private void finishLink(UUID userId, GoogleIdentity identity, HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        try {
            sessions.establish(google.link(userId, identity), request, response);
            response.sendRedirect(properties.pageUrl(ACCOUNT_PAGE));
        } catch (ApiException | DataIntegrityViolationException ex) {
            restoreOrTerminate(userId, request, response);
            redirectWithError(ACCOUNT_PAGE, errorCode(ex), response);
        }
    }

    private void restoreOrTerminate(UUID userId, HttpServletRequest request, HttpServletResponse response) {
        try {
            SessionUser user = google.sessionUser(userId);
            sessions.establish(user, request, response);
        } catch (ApiException ex) {
            sessions.terminate(request, response);
        }
    }

    /**
     * Falhas antes de haver identidade: login cancelado, state inválido ou erro no Google.
     */
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String page = GoogleLinkIntent.take(request).isPresent() ? ACCOUNT_PAGE : LOGIN_PAGE;
        redirectWithError(page, GENERIC_ERROR, response);
    }

    private void redirectWithError(String page, String code, HttpServletResponse response) throws IOException {
        response.sendRedirect(properties.pageUrl(page + "?error=" + code));
    }

    private static String errorCode(RuntimeException ex) {
        return ex instanceof ApiException apiException ? apiException.getCode() : GENERIC_ERROR;
    }
}
