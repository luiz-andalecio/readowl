package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.auth.application.GoogleAccountService;
import br.com.readowl.readowl.auth.application.SessionManager;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Só existe com GOOGLE_CLIENT_ID definido: sem credenciais, a aplicação sobe com o login do Google
 * desativado. Os clientes HTTP padrão do Spring Security não têm timeout, então são configurados
 * aqui para que um Google lento não prenda as threads de requisição.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${GOOGLE_CLIENT_ID:}'.isBlank()")
class GoogleOAuthConfiguration {

    private static final String REGISTRATION_ID = "google";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PENDING_LOGIN_VALIDITY = Duration.ofMinutes(10);
    private static final int BEFORE_SECURITY_FILTERS = -200;

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(@Value("${GOOGLE_CLIENT_ID}") String clientId,
                                                              @Value("${GOOGLE_CLIENT_SECRET}") String clientSecret,
                                                              ReadowlProperties properties) {
        // Endereço fixo: precisa ser idêntico ao cadastrado no Google e não deve depender de cabeçalhos.
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder(REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .redirectUri(properties.pageUrl("/login/oauth2/code/" + REGISTRATION_ID))
                .build());
    }

    /**
     * Antes da cadeia de segurança, onde o pedido ao Google criaria a sessão.
     */
    @Bean
    FilterRegistrationBean<GoogleLoginRateLimitFilter> googleLoginRateLimit(RateLimiter rateLimiter,
                                                                            ReadowlProperties properties) {
        FilterRegistrationBean<GoogleLoginRateLimitFilter> registration =
                new FilterRegistrationBean<>(new GoogleLoginRateLimitFilter(rateLimiter, properties));
        registration.addUrlPatterns("/oauth2/authorization/*");
        registration.setOrder(BEFORE_SECURITY_FILTERS);
        return registration;
    }

    @Bean
    GoogleLoginHandler googleLoginHandler(GoogleAccountService google, SessionManager sessions,
                                          ReadowlProperties properties) {
        return new GoogleLoginHandler(google, sessions, properties);
    }

    @Bean
    Customizer<OAuth2LoginConfigurer<HttpSecurity>> googleLogin(GoogleLoginHandler handler,
                                                                ClientRegistrationRepository registrations) {
        return login -> login
                .clientRegistrationRepository(registrations)
                // Impede a página de login gerada pelo Spring Security. A rota real é do frontend.
                .loginPage("/login")
                .authorizationEndpoint(endpoint -> endpoint
                        .authorizationRequestRepository(new ShortLivedAuthorizationRequests()))
                .authorizedClientRepository(new DiscardingAuthorizedClientRepository())
                .successHandler(handler)
                .failureHandler(handler);
    }

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> googleTokenClient() {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(RestClient.builder()
                .requestFactory(requestFactory())
                .configureMessageConverters(converters -> converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        return client;
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> googleIdTokenDecoderFactory() {
        RestTemplate restTemplate = new RestTemplate(requestFactory());
        // Reaproveita o decoder a cada login: é ele que mantém as chaves do Google em cache.
        Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();
        return registration -> decoders.computeIfAbsent(registration.getRegistrationId(), id -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                    .restOperations(restTemplate)
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(), new OidcIdTokenValidator(registration)));
            decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
            return decoder;
        });
    }

    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> googleUserService() {
        RestTemplate restTemplate = new RestTemplate(requestFactory());
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        DefaultOAuth2UserService userInfoService = new DefaultOAuth2UserService();
        userInfoService.setRestOperations(restTemplate);
        OidcUserService service = new OidcUserService();
        service.setOauth2UserService(userInfoService);
        return service;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /**
     * O pedido ao Google fica na sessão, então qualquer visitante cria uma linha só de abrir a rota. Uma sessão
     * criada aqui vale por pouco tempo, até o login terminar. A de quem já está logado, ao vincular a conta, não muda.
     */
    private static final class ShortLivedAuthorizationRequests
            implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

        private final HttpSessionOAuth2AuthorizationRequestRepository delegate =
                new HttpSessionOAuth2AuthorizationRequestRepository();

        @Override
        public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
            return delegate.loadAuthorizationRequest(request);
        }

        @Override
        public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                             HttpServletRequest request, HttpServletResponse response) {
            boolean creatingSession = request.getSession(false) == null;
            delegate.saveAuthorizationRequest(authorizationRequest, request, response);
            if (creatingSession && authorizationRequest != null) {
                request.getSession().setMaxInactiveInterval((int) PENDING_LOGIN_VALIDITY.toSeconds());
            }
        }

        @Override
        public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                     HttpServletResponse response) {
            return delegate.removeAuthorizationRequest(request, response);
        }
    }

    /**
     * O padrão manteria os tokens do Google em memória, mas o login não os usa depois de validar a identidade.
     */
    private static final class DiscardingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                                                                         Authentication principal,
                                                                         HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                                         HttpServletRequest request, HttpServletResponse response) {
            // Descartado de propósito.
        }

        @Override
        public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                                           HttpServletRequest request, HttpServletResponse response) {
            // Nada é guardado.
        }
    }
}
