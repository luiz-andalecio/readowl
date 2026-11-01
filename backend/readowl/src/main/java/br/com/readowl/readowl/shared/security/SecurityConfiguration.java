package br.com.readowl.readowl.shared.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {

    private static final String[] PUBLIC_READS = {
            "/api/v1/auth/csrf",
            "/api/v1/auth/session",
            "/api/v1/users/{username}",
            "/api/v1/genres",
            "/api/v1/tags",
            "/api/v1/books",
            "/api/v1/books/{slug}",
            "/api/v1/books/{slug}/chapters",
            "/api/v1/books/{slug}/chapters/{chapterSlug}",
            "/api/v1/books/{bookId}/comments",
            "/api/v1/books/{bookId}/rating",
            "/api/v1/chapters/{chapterId}/comments",
            "/api/v1/chapters/{chapterId}/reactions",
            "/api/v1/comments/{commentId}/replies",
            "/api/v1/banners"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemSecurityHandler problemHandler,
                                            CsrfTokenRepository csrfTokenRepository,
                                            SecurityContextRepository securityContextRepository,
                                            SessionAccessCheck sessionAccessCheck,
                                            ObjectProvider<Customizer<OAuth2LoginConfigurer<HttpSecurity>>> oauth2Login,
                                            Environment environment)
            throws Exception {
        oauth2Login.ifAvailable(http::oauth2Login);
        // Na porta interna de produção, que não é publicada, não há login. A regra não vale para a porta pública.
        boolean internalPort = ManagementPortType.get(environment) == ManagementPortType.DIFFERENT;
        return http
                .addFilterBefore(new SessionAccessFilter(sessionAccessCheck), AuthorizationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(internalPort ? EndpointRequest.toAnyEndpoint()
                                : EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .requestMatchers(HttpMethod.GET, "/livez", "/readyz").permitAll()
                        // Editar usuários é exclusivo do root; o restante da administração, de admin e root.
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/users/**").hasRole("ROOT")
                        .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "ROOT")
                        .requestMatchers("/api/v1/moderation/**").hasAnyRole("MODERATOR", "ADMIN", "ROOT")
                        // Antes da regra pública de perfis, que também casaria com "me".
                        .requestMatchers("/api/v1/users/me", "/api/v1/users/me/**").authenticated()
                        .requestMatchers(HttpMethod.GET, PUBLIC_READS).permitAll()
                        // Monitores e verificadores de links usam HEAD.
                        .requestMatchers(HttpMethod.HEAD, PUBLIC_READS).permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/verify-email",
                                "/api/v1/auth/resend-verification",
                                "/api/v1/auth/login",
                                "/api/v1/auth/password/forgot",
                                "/api/v1/auth/password/reset").permitAll()
                        .anyRequest().authenticated())
                // Sem csrf.spa(): ele emite o cookie em toda resposta, o que impediria o cache de
                // páginas públicas. Aqui o token só é criado em GET /auth/csrf ou quando validado.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                // Sem cache de requisição: uma resposta 401 não pode criar sessão para visitantes.
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .build();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(@Value("${readowl.csrf-cookie-name}") String cookieName) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(cookieName);
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }
}
