package br.com.readowl.readowl.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Servidor OpenID Connect local no lugar do Google, com ID token assinado de verdade. Assim os testes
 * passam pelo fluxo real do Spring Security: troca de código, validação de assinatura, emissor,
 * audiência e nonce, além dos clientes HTTP configurados pela aplicação.
 */
public final class FakeGoogle {

    public static final String CLIENT_ID = "client-id-de-teste";
    public static final String ACCESS_TOKEN = "fake-google-access-token-rastreavel";

    private final HttpServer server;
    private final RSAKey key;
    private final String issuer;
    private volatile Person person;
    private volatile String nonce;
    private volatile String tokenAudience = CLIENT_ID;

    public record Person(String subject, String email, boolean emailVerified, String name) {
    }

    public FakeGoogle() {
        try {
            key = new RSAKeyGenerator(2048).keyID("fake-key").generate();
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException | JOSEException ex) {
            throw new IllegalStateException(ex);
        }
        issuer = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/token", this::token);
        server.createContext("/jwks", this::jwks);
        server.createContext("/userinfo", this::userInfo);
        server.start();
    }

    /**
     * A mesma configuração da aplicação, apontando para este servidor.
     */
    public ClientRegistration registration() {
        return CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(CLIENT_ID)
                .clientSecret("segredo-de-teste")
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri(issuer + "/authorize")
                .tokenUri(issuer + "/token")
                .jwkSetUri(issuer + "/jwks")
                .userInfoUri(issuer + "/userinfo")
                .issuerUri(issuer)
                .build();
    }

    /**
     * Define quem se autentica na próxima troca de código e o nonce recebido no redirecionamento.
     */
    public void authorize(Person person, String nonce) {
        this.person = person;
        this.nonce = nonce;
        this.tokenAudience = CLIENT_ID;
    }

    public void issueTokensForAnotherClient() {
        this.tokenAudience = "outro-cliente";
    }

    private void token(HttpExchange exchange) throws IOException {
        String body = """
                {"access_token":"%s","token_type":"Bearer","expires_in":3600,
                 "scope":"openid profile email","id_token":"%s"}
                """.formatted(ACCESS_TOKEN, idToken());
        respond(exchange, body);
    }

    private void jwks(HttpExchange exchange) throws IOException {
        respond(exchange, new JWKSet(key.toPublicJWK()).toString());
    }

    private void userInfo(HttpExchange exchange) throws IOException {
        Person current = person;
        respond(exchange, """
                {"sub":"%s","email":"%s","email_verified":%s,"name":"%s"}
                """.formatted(current.subject(), current.email(), current.emailVerified(), current.name()));
    }

    private String idToken() {
        Person current = person;
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(current.subject())
                .audience(List.of(tokenAudience))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", nonce)
                .claim("email", current.email())
                .claim("email_verified", current.emailVerified())
                .claim("name", current.name())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
        return jwt.serialize();
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (exchange) {
            exchange.getResponseBody().write(bytes);
        }
    }
}
