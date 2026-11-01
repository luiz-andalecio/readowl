package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.ratelimit.ClientAddress;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Cada início de login com Google cria uma sessão, mesmo para visitantes. O navegador está saindo do site nesse
 * momento, então quem passa do limite volta à página de login em vez de receber um JSON.
 */
class GoogleLoginRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ReadowlProperties properties;

    GoogleLoginRateLimitFilter(RateLimiter rateLimiter, ReadowlProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (rateLimiter.tryAcquire(RateLimitPolicy.OAUTH_START_IP, ClientAddress.of(request))) {
            chain.doFilter(request, response);
        } else {
            response.sendRedirect(properties.pageUrl("/login?error=RATE_LIMITED"));
        }
    }
}
