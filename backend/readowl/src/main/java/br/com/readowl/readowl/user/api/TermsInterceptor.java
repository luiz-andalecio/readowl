package br.com.readowl.readowl.user.api;

import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Sem aceitar os termos vigentes, a conta só lê e cuida de si mesma: aceitar, sair, encerrar sessões, trocar a senha ou o
 * e-mail e excluir a conta.
 * Atinge quem entrou pelo Google, que não passa pelo formulário de cadastro, e todos quando os termos mudam.
 */
@Component
class TermsInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String AUTH_PATH = "/api/v1/auth/";
    private static final Set<String> ACCOUNT_CARE_PATHS = Set.of("/api/v1/users/me/terms", "/api/v1/users/me/password",
            "/api/v1/users/me/email");
    private static final String SESSIONS_PATH = "/api/v1/users/me/sessions";
    private static final String ACCOUNT_PATH = "/api/v1/users/me";

    private final UserService users;

    TermsInterceptor(UserService users) {
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (SAFE_METHODS.contains(request.getMethod()) || isExempt(request)) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionUser user
                && !users.hasAcceptedCurrentTerms(user.id())) {
            throw ApiException.forbidden("TERMS_NOT_ACCEPTED", "Aceite os termos de uso para continuar.");
        }
        return true;
    }

    private static boolean isExempt(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith(AUTH_PATH) || ACCOUNT_CARE_PATHS.contains(path) || path.startsWith(SESSIONS_PATH)
                || (path.equals(ACCOUNT_PATH) && "DELETE".equals(request.getMethod()));
    }
}
