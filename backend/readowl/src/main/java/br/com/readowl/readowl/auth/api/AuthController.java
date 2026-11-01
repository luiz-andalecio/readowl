package br.com.readowl.readowl.auth.api;

import br.com.readowl.readowl.auth.application.GoogleAccountService;
import br.com.readowl.readowl.auth.application.GoogleLinkIntent;
import br.com.readowl.readowl.auth.application.LoginService;
import br.com.readowl.readowl.auth.application.PasswordService;
import br.com.readowl.readowl.auth.application.RegistrationService;
import br.com.readowl.readowl.auth.application.SessionManager;
import br.com.readowl.readowl.auth.application.SessionResponse;
import br.com.readowl.readowl.shared.ratelimit.ClientAddress;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final RegistrationService registration;
    private final LoginService login;
    private final PasswordService passwords;
    private final GoogleAccountService google;
    private final SessionManager sessions;

    AuthController(RegistrationService registration, LoginService login, PasswordService passwords,
                   GoogleAccountService google, SessionManager sessions) {
        this.registration = registration;
        this.login = login;
        this.passwords = passwords;
        this.google = google;
        this.sessions = sessions;
    }

    /**
     * Ler o token faz o cookie XSRF-TOKEN ser emitido; o valor é lido do cookie pelo frontend.
     */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void csrf(CsrfToken token) {
        token.getToken();
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        registration.register(body.name(), body.username(), body.email(), body.password(), ClientAddress.of(request));
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyEmail(@Valid @RequestBody TokenRequest body, HttpServletRequest request) {
        registration.verifyEmail(body.token(), ClientAddress.of(request));
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resendVerification(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        registration.resendVerification(body.email(), ClientAddress.of(request));
    }

    @PostMapping("/login")
    SessionResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                          HttpServletResponse response) {
        SessionUser user = login.authenticate(body.email(), body.password(), ClientAddress.of(request));
        sessions.establish(user, request, response);
        return login.describe(user);
    }

    @GetMapping("/session")
    SessionResponse session(@AuthenticationPrincipal SessionUser principal) {
        return login.describe(principal);
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgotPassword(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        passwords.requestReset(body.email(), ClientAddress.of(request));
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request) {
        passwords.reset(body.token(), body.newPassword(), ClientAddress.of(request));
    }

    /**
     * Depois desta resposta, o frontend navega para /oauth2/authorization/google.
     */
    @PostMapping("/google/link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void linkGoogle(@AuthenticationPrincipal SessionUser principal, @Valid @RequestBody GoogleLinkRequest body,
                    HttpServletRequest request) {
        google.authorizeLink(principal, body.password());
        GoogleLinkIntent.store(principal.id(), request);
    }
}
