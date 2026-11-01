package br.com.readowl.readowl.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Suspender ou trocar o papel de uma conta encerra as sessões dela. Se isso falhar, ou se um login terminar logo
 * depois, a sessão guardaria um papel antigo. Por isso, escritas, rotas administrativas e qualquer requisição de quem
 * tem um papel de moderação ou administração conferem a conta a cada vez: esse papel também abre leituras em rotas comuns,
 * como rascunhos de outras pessoas. As leituras das demais contas não ganham uma consulta a mais.
 */
class SessionAccessFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String ADMIN_PATH = "/api/v1/admin/";
    private static final String MODERATION_PATH = "/api/v1/moderation/";

    private final SessionAccessCheck check;

    SessionAccessFilter(SessionAccessCheck check) {
        this.check = check;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            HttpSession session = request.getSession(false);
            boolean expired = session != null && !check.isWithinMaxAge(authentication, session);
            boolean needsCheck = needsCheck(request) || check.isPrivileged(authentication);
            if (expired || (needsCheck && !check.isCurrent(authentication))) {
                check.reject(request, response);
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean needsCheck(HttpServletRequest request) {
        String path = request.getServletPath();
        return !SAFE_METHODS.contains(request.getMethod()) || path.startsWith(ADMIN_PATH) || path.startsWith(MODERATION_PATH);
    }
}
