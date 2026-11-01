package br.com.readowl.readowl.auth.api;

import br.com.readowl.readowl.auth.application.EmailChangeService;
import br.com.readowl.readowl.auth.application.SessionManager;
import br.com.readowl.readowl.shared.ratelimit.ClientAddress;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users/me")
class AccountSecurityController {

    private final SessionManager sessions;
    private final EmailChangeService emailChanges;

    AccountSecurityController(SessionManager sessions, EmailChangeService emailChanges) {
        this.sessions = sessions;
        this.emailChanges = emailChanges;
    }

    @GetMapping("/sessions")
    List<SessionManager.SessionView> sessions(@AuthenticationPrincipal SessionUser principal, HttpServletRequest request) {
        return sessions.list(principal, request);
    }

    /**
     * Encerra as demais sessões e mantém a atual.
     */
    @DeleteMapping("/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeOtherSessions(@AuthenticationPrincipal SessionUser principal, HttpServletRequest request) {
        sessions.revokeOthers(principal, request);
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeSession(@AuthenticationPrincipal SessionUser principal, @PathVariable String id,
                       HttpServletRequest request, HttpServletResponse response) {
        sessions.revoke(principal, id, request, response);
    }

    @PostMapping("/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestEmailChange(@AuthenticationPrincipal SessionUser principal, @Valid @RequestBody ChangeEmailRequest body,
                            HttpServletRequest request) {
        emailChanges.request(principal, body.password(), body.newEmail(), ClientAddress.of(request));
    }

    /**
     * @param password obrigatória para quem tem senha
     */
    record ChangeEmailRequest(@NotBlank @Email @Size(max = 255) String newEmail, @Size(max = 128) String password) {

        // Os corpos de requisição podem aparecer em logs de depuração do Spring MVC.
        @Override
        public String toString() {
            return "ChangeEmailRequest[]";
        }
    }
}
