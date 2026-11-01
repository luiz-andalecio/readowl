package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.shared.security.SessionAccessCheck;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
class AccountSessionCheck implements SessionAccessCheck {

    private final UserService users;
    private final SessionManager sessions;

    AccountSessionCheck(UserService users, SessionManager sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    /**
     * Visitantes e o retorno do Google, que ainda não têm uma conta na sessão, não têm o que conferir.
     */
    @Override
    public boolean isCurrent(Authentication authentication) {
        return !(authentication.getPrincipal() instanceof SessionUser user) || users.hasAccess(user.id(), user.role());
    }

    @Override
    public boolean isPrivileged(Authentication authentication) {
        return authentication.getPrincipal() instanceof SessionUser user && user.role() != Role.USER;
    }

    @Override
    public boolean isWithinMaxAge(Authentication authentication, HttpSession session) {
        return !(authentication.getPrincipal() instanceof SessionUser user) || sessions.isWithinMaxAge(user, session);
    }

    @Override
    public void reject(HttpServletRequest request, HttpServletResponse response) {
        sessions.terminate(request, response);
    }
}
