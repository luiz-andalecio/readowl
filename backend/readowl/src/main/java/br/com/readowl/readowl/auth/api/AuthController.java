package br.com.readowl.readowl.auth.api;

import br.com.readowl.readowl.auth.application.EmailChangeService;
import br.com.readowl.readowl.auth.application.GoogleAccountService;
import br.com.readowl.readowl.auth.application.GoogleLinkIntent;
import br.com.readowl.readowl.auth.application.LoginService;
import br.com.readowl.readowl.auth.application.PasswordService;
import br.com.readowl.readowl.auth.application.RegistrationService;
import br.com.readowl.readowl.auth.application.SessionManager;
import br.com.readowl.readowl.auth.application.SessionResponse;
import br.com.readowl.readowl.shared.api.PublicCache;
import br.com.readowl.readowl.shared.ratelimit.ClientAddress;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private static final String DEVICE_COOKIE = "READOWL_DEVICE";
    private static final Duration DEVICE_COOKIE_MAX_AGE = Duration.ofDays(90);

    private final RegistrationService registration;
    private final LoginService login;
    private final PasswordService passwords;
    private final GoogleAccountService google;
    private final SessionManager sessions;
    private final EmailChangeService emailChanges;
    private final UserService users;

    AuthController(RegistrationService registration, LoginService login, PasswordService passwords,
                   GoogleAccountService google, SessionManager sessions, EmailChangeService emailChanges,
                   UserService users) {
        this.emailChanges = emailChanges;
        this.users = users;
        this.registration = registration;
        this.login = login;
        this.passwords = passwords;
        this.google = google;
        this.sessions = sessions;
    }

    /**
     * Ler o token faz o cookie CSRF ser emitido; o valor é lido do cookie pelo frontend.
     */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void csrf(CsrfToken token) {
        token.getToken();
    }

    @GetMapping("/terms")
    @PublicCache
    UserService.CurrentTerms terms() {
        return users.currentTerms();
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        registration.register(body.name(), body.username(), body.email(), body.password(), body.termsVersion(),
                ClientAddress.of(request));
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
    SessionResponse login(@Valid @RequestBody LoginRequest body,
                          @CookieValue(name = DEVICE_COOKIE, required = false) String deviceToken,
                          HttpServletRequest request, HttpServletResponse response) {
        LoginService.LoginResult result = login.authenticate(body.email(), body.password(), ClientAddress.of(request), deviceToken);
        sessions.establish(result.user(), request, response, Boolean.TRUE.equals(body.rememberMe()));
        if (result.newDeviceToken() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, deviceCookie(result.newDeviceToken(), request).toString());
        }
        return login.describe(result.user());
    }

    /**
     * Só vai para a rota de login e não identifica ninguém sem o e-mail e a senha.
     */
    private static ResponseCookie deviceCookie(String token, HttpServletRequest request) {
        return ResponseCookie.from(DEVICE_COOKIE, token)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path("/api/v1/auth/login")
                .maxAge(DEVICE_COOKIE_MAX_AGE)
                .build();
    }

    @PostMapping("/email/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmEmailChange(@Valid @RequestBody TokenRequest body, HttpServletRequest request) {
        emailChanges.confirm(body.token(), ClientAddress.of(request));
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
