package br.com.readowl.readowl.auth;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiTestConfiguration;
import br.com.readowl.readowl.support.FakeGoogle;
import br.com.readowl.readowl.support.RecordingMailSender;
import br.com.readowl.readowl.support.TestDatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"GOOGLE_CLIENT_ID=" + FakeGoogle.CLIENT_ID, "GOOGLE_CLIENT_SECRET=segredo-de-teste",
                "readowl.privileged-google-login-required=true"})
@ActiveProfiles("test")
@Import({TestDatabaseConfiguration.class, ApiTestConfiguration.class, GoogleLoginIntegrationTest.FakeGoogleConfiguration.class})
class GoogleLoginIntegrationTest {

    private static final String SESSION = "/api/v1/auth/session";
    private static final String START = "/oauth2/authorization/google";
    private static final String CALLBACK = "/login/oauth2/code/google";
    private static final String LINK = "/api/v1/auth/google/link";
    private static final String HOME = "https://readowl.test/";
    private static final int TWELVE_HOURS_IN_SECONDS = 12 * 60 * 60;

    private static final FakeGoogle GOOGLE = new FakeGoogle();

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeGoogleConfiguration {

        @Bean
        @Primary
        ClientRegistrationRepository fakeGoogleRegistration() {
            return new InMemoryClientRegistrationRepository(GOOGLE.registration());
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
    }

    private static FakeGoogle.Person person(String email) {
        return new FakeGoogle.Person(UUID.randomUUID().toString(), email, true, "Maria Conceição da Silva");
    }

    /**
     * Percorre o caminho do navegador: ida para o Google e volta com o código de autorização.
     */
    private ApiClient.Response signIn(ApiClient client, FakeGoogle.Person person) {
        ApiClient.Response start = client.get(START);
        assertThat(start.status()).isEqualTo(302);
        Map<String, String> parameters = UriComponentsBuilder.fromUri(URI.create(start.location())).build()
                .getQueryParams().toSingleValueMap();
        GOOGLE.authorize(person, parameters.get("nonce"));
        return client.get(CALLBACK + "?code=codigo-de-teste&state=" + parameters.get("state"));
    }

    private int sessionTimeout(ApiClient client) {
        String sessionId = new String(Base64.getDecoder().decode(client.cookie("SESSION").orElseThrow()), StandardCharsets.UTF_8);
        return jdbc.queryForObject("SELECT max_inactive_interval FROM spring_session WHERE session_id = ?", Integer.class, sessionId);
    }

    @Test
    void aSessionCreatedOnlyToStartTheLoginIsShortLived() {
        ApiClient visitor = accounts.client();

        visitor.get(START);

        assertThat(sessionTimeout(visitor)).isEqualTo(600);
    }

    @Test
    void finishingTheLoginGivesTheNormalSessionLifetime() {
        ApiClient client = accounts.client();

        signIn(client, person(uniqueEmail()));

        assertThat(sessionTimeout(client)).isEqualTo(TWELVE_HOURS_IN_SECONDS);
    }

    private static final String GOOGLE_PICTURE = "https://lh3.googleusercontent.com/a/foto";

    private static FakeGoogle.Person withPicture(String email, String subject, String picture) {
        return new FakeGoogle.Person(subject, email, true, "Pessoa", picture);
    }

    private String avatarOf(ApiClient client) {
        return client.get("/api/v1/users/me").json().at("/avatarUrl").asString(null);
    }

    @Test
    void theGooglePictureBecomesTheProfilePictureAndFollowsChanges() {
        String email = uniqueEmail();
        String subject = UUID.randomUUID().toString();
        ApiClient client = accounts.client();

        signIn(client, withPicture(email, subject, GOOGLE_PICTURE));
        assertThat(avatarOf(client)).isEqualTo(GOOGLE_PICTURE);

        ApiClient again = accounts.client();
        signIn(again, withPicture(email, subject, GOOGLE_PICTURE + "-nova"));
        assertThat(avatarOf(again)).isEqualTo(GOOGLE_PICTURE + "-nova");
    }

    @Test
    void anUploadedPictureIsNotReplacedByTheGoogleOne() throws Exception {
        String email = uniqueEmail();
        String subject = UUID.randomUUID().toString();
        ApiClient client = accounts.client();
        signIn(client, withPicture(email, subject, GOOGLE_PICTURE));
        jdbc.update("UPDATE users SET avatar_key = 'avatars/x.jpg', avatar_url = 'https://cdn.readowl.test/avatars/x.jpg', avatar_from_login = false WHERE email = ?", email);

        ApiClient again = accounts.client();
        signIn(again, withPicture(email, subject, GOOGLE_PICTURE));

        assertThat(avatarOf(again)).isEqualTo("https://cdn.readowl.test/avatars/x.jpg");
    }

    @Test
    void aHostedImageReplacesTheGooglePictureAndStaysAfterTheNextLogin() {
        String hosted = "https://i.imgur.com/minha-foto.jpg";
        String email = uniqueEmail();
        String subject = UUID.randomUUID().toString();
        ApiClient client = accounts.client();
        signIn(client, withPicture(email, subject, GOOGLE_PICTURE));
        assertThat(client.post("/api/v1/users/me/terms", body("termsVersion", AccountFixtures.TERMS_VERSION, "ageConfirmed", true)).status()).isEqualTo(204);

        assertThat(client.put("/api/v1/users/me/avatar", body("url", hosted)).json().at("/avatarUrl").asString()).isEqualTo(hosted);

        ApiClient again = accounts.client();
        signIn(again, withPicture(email, subject, GOOGLE_PICTURE + "-nova"));
        assertThat(avatarOf(again)).isEqualTo(hosted);
    }

    @Test
    void aRemovedPictureDoesNotComeBackInTheNextLogin() {
        String email = uniqueEmail();
        String subject = UUID.randomUUID().toString();
        ApiClient client = accounts.client();
        signIn(client, withPicture(email, subject, GOOGLE_PICTURE));
        assertThat(client.delete("/api/v1/users/me/avatar").status()).isEqualTo(204);

        ApiClient again = accounts.client();
        signIn(again, withPicture(email, subject, GOOGLE_PICTURE));

        assertThat(avatarOf(again)).isNull();
    }

    @Test
    void aPictureFromAnotherSiteIsIgnored() {
        ApiClient client = accounts.client();

        signIn(client, withPicture(uniqueEmail(), UUID.randomUUID().toString(), "https://rastreador.example.com/foto"));

        assertThat(avatarOf(client)).isNull();
    }

    @Test
    void linkingGoogleUsesThePictureOnlyWithoutOne() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);
        assertThat(client.post(LINK, body("password", PASSWORD)).status()).isEqualTo(204);

        signIn(client, withPicture(uniqueEmail(), UUID.randomUUID().toString(), GOOGLE_PICTURE));

        assertThat(avatarOf(client)).isEqualTo(GOOGLE_PICTURE);
    }

    private void linkGoogleTo(String email) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, provider, provider_account_id, created_at)
                VALUES (uuidv7(), ?, 'google', ?, now())
                """, accounts.userId(email), UUID.randomUUID().toString());
    }

    @Test
    void aPrivilegedAccountEntersThroughGoogleWithAShortSessionAndOnlyPromotesWhoHasGoogle() {
        FakeGoogle.Person person = person(uniqueEmail());
        ApiClient root = accounts.client();
        signIn(root, person);
        jdbc.update("UPDATE users SET role = 'ROOT', terms_version = ? WHERE email = ?", AccountFixtures.TERMS_VERSION, person.email());
        root = accounts.client();
        signIn(root, person);
        assertThat(sessionTimeout(root)).isEqualTo(3600);

        String without = uniqueEmail();
        accounts.registerVerified(without);
        var refused = root.patch("/api/v1/admin/users/" + accounts.userId(without), body("role", "MODERATOR"));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("GOOGLE_REQUIRED_FOR_ROLE");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, without)).isEqualTo("USER");

        String with = uniqueEmail();
        accounts.registerVerified(with);
        linkGoogleTo(with);
        assertThat(root.patch("/api/v1/admin/users/" + accounts.userId(with), body("role", "MODERATOR")).status()).isEqualTo(200);
        var password = accounts.client().post("/api/v1/auth/login", body("email", with, "password", PASSWORD));
        assertThat(password.status()).isEqualTo(403);
        assertThat(password.code()).isEqualTo("GOOGLE_LOGIN_REQUIRED");
    }

    @Test
    void aGoogleAccountAcceptsTheTermsBeforeWriting() {
        ApiClient client = accounts.client();
        signIn(client, person(uniqueEmail()));

        assertThat(client.get(SESSION).json().at("/user/termsAccepted").asBoolean()).isFalse();
        assertThat(client.patch("/api/v1/users/me", body("description", "Oi")).code()).isEqualTo("TERMS_NOT_ACCEPTED");

        assertThat(client.post("/api/v1/users/me/terms", body("termsVersion", AccountFixtures.TERMS_VERSION, "ageConfirmed", true)).status()).isEqualTo(204);
        assertThat(client.patch("/api/v1/users/me", body("description", "Oi")).status()).isEqualTo(200);
    }

    @Test
    void anAccountWithoutPasswordConfirmsSensitiveActionsWithARecentLogin() {
        FakeGoogle.Person person = person(uniqueEmail());
        ApiClient client = accounts.client();
        signIn(client, person);
        UUID id = accounts.userId(person.email());
        jdbc.update("UPDATE spring_session_attributes SET attribute_bytes = ? WHERE attribute_name = 'readowl.authenticatedAt' AND session_primary_id IN (SELECT primary_id FROM spring_session WHERE principal_name = ?)",
                serialized(System.currentTimeMillis() / 1000 - 3600), id.toString());

        var stale = client.delete("/api/v1/users/me", body());
        assertThat(stale.status()).isEqualTo(403);
        assertThat(stale.code()).isEqualTo("RECENT_LOGIN_REQUIRED");

        signIn(client, person);
        assertThat(client.delete("/api/v1/users/me", body()).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE user_id = ?", Integer.class, id)).isZero();
    }

    private static byte[] serialized(long value) {
        try (var bytes = new java.io.ByteArrayOutputStream(); var out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(Long.valueOf(value));
            out.flush();
            return bytes.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void startingToLinkDoesNotShortenTheSessionOfALoggedInUser() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);

        assertThat(client.post(LINK, body("password", PASSWORD)).status()).isEqualTo(204);
        client.get(START);

        assertThat(sessionTimeout(client)).isEqualTo(TWELVE_HOURS_IN_SECONDS);
    }

    @Test
    void anAccountAlreadyLinkedCannotStartAnotherLink() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);
        assertThat(client.post(LINK, body("password", PASSWORD)).status()).isEqualTo(204);
        signIn(client, person(uniqueEmail()));

        ApiClient.Response again = client.post(LINK, body("password", PASSWORD));

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("GOOGLE_ALREADY_LINKED");
    }

    @Test
    void twoLinksStartedAtTheSameTimeKeepOnlyTheFirst() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient first = accounts.loggedIn(email, PASSWORD);
        ApiClient second = accounts.loggedIn(email, PASSWORD);
        assertThat(first.post(LINK, body("password", PASSWORD)).status()).isEqualTo(204);
        assertThat(second.post(LINK, body("password", PASSWORD)).status()).isEqualTo(204);
        signIn(first, person(uniqueEmail()));

        ApiClient.Response refused = signIn(second, person(uniqueEmail()));

        assertThat(refused.location()).contains("/conta?error=GOOGLE_ALREADY_LINKED");
        assertThat(second.get(SESSION).json().at("/authenticated").asBoolean()).isTrue();
    }

    @Test
    void anAccountSuspendedDuringTheLinkLosesTheSession() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);
        assertThat(client.post(LINK, body("password", PASSWORD)).status()).isEqualTo(204);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        ApiClient.Response refused = signIn(client, person(uniqueEmail()));

        assertThat(refused.location()).contains("/conta?error=ACCOUNT_SUSPENDED");
        assertThat(client.get(SESSION).json().at("/authenticated").asBoolean()).isFalse();
    }

    @Test
    void aGoogleAccountWithoutAUsableNameStillGetsAProfile() {
        ApiClient blank = accounts.client();
        ApiClient symbols = accounts.client();

        signIn(blank, new FakeGoogle.Person(UUID.randomUUID().toString(), uniqueEmail(), true, " "));
        signIn(symbols, new FakeGoogle.Person(UUID.randomUUID().toString(), uniqueEmail(), true, "!!"));

        assertThat(blank.get(SESSION).json().at("/user/name").asString()).isEqualTo("Leitor Readowl");
        assertThat(symbols.get(SESSION).json().at("/user/username").asString()).startsWith("leitor-");
    }

    @Test
    void startingTheLoginIsLimitedPerAddress() {
        ApiClient.Response last = null;
        for (int attempt = 0; attempt < 31; attempt++) {
            last = accounts.client().get(START);
        }

        assertThat(last.status()).isEqualTo(302);
        assertThat(last.location()).isEqualTo("https://readowl.test/login?error=RATE_LIMITED");
        assertThat(last.setCookies()).as("sem criar sessão").isEmpty();
    }

    @Test
    void redirectGoesToTheProviderWithStateAndNonce() {
        ApiClient.Response start = accounts.client().get(START);

        assertThat(start.status()).isEqualTo(302);
        assertThat(start.location()).contains("/authorize?").contains("state=").contains("nonce=")
                .contains("client_id=" + FakeGoogle.CLIENT_ID).contains("scope=openid%20profile%20email");
    }

    @Test
    void firstLoginCreatesAVerifiedAccountWithoutPassword() {
        String email = uniqueEmail();
        ApiClient client = accounts.client();

        ApiClient.Response callback = signIn(client, person(email));

        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.location()).isEqualTo(HOME);

        ApiClient.Response session = client.get(SESSION);
        assertThat(session.json().at("/authenticated").asBoolean()).isTrue();
        assertThat(session.json().at("/user/hasPassword").asBoolean()).isFalse();
        assertThat(session.json().at("/user/googleLinked").asBoolean()).isTrue();
        assertThat(session.json().at("/user/name").asString()).isEqualTo("Maria Conceição da Silva");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT username, password_hash, email_verified_at FROM users WHERE email = ?", email);
        assertThat(row.get("password_hash")).isNull();
        assertThat(row.get("email_verified_at")).isNotNull();
        assertThat((String) row.get("username")).startsWith("maria-conceicao-da-s").doesNotContain(email.split("@")[0]);
    }

    @Test
    void googleTokensAreNotStoredInTheSessionOrInTheDatabase() {
        String email = uniqueEmail();
        ApiClient client = accounts.client();
        signIn(client, person(email));
        assertThat(client.get(SESSION).json().at("/authenticated").asBoolean()).isTrue();

        UUID userId = accounts.userId(email);
        Integer attributes = jdbc.queryForObject("""
                SELECT count(*) FROM spring_session_attributes a
                JOIN spring_session s ON s.primary_id = a.session_primary_id
                WHERE s.principal_name = ?
                """, Integer.class, userId.toString());
        Integer tokenMatches = jdbc.queryForObject("""
                SELECT count(*) FROM spring_session_attributes
                WHERE position(convert_to(?, 'UTF8') IN attribute_bytes) > 0
                   OR position('OidcIdToken'::bytea IN attribute_bytes) > 0
                   OR position('OAuth2AuthenticationToken'::bytea IN attribute_bytes) > 0
                """, Integer.class, FakeGoogle.ACCESS_TOKEN);

        assertThat(attributes).isPositive();
        assertThat(tokenMatches).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM accounts WHERE user_id = ?", userId))
                .containsOnlyKeys("id", "user_id", "provider", "provider_account_id", "created_at");
    }

    @Test
    void returningLoginFindsTheSameAccountEvenIfTheEmailChangedAtGoogle() {
        String email = uniqueEmail();
        FakeGoogle.Person person = person(email);
        signIn(accounts.client(), person);
        UUID userId = accounts.userId(email);

        ApiClient client = accounts.client();
        ApiClient.Response callback = signIn(client,
                new FakeGoogle.Person(person.subject(), uniqueEmail(), true, person.name()));

        assertThat(callback.location()).isEqualTo(HOME);
        assertThat(client.get(SESSION).json().at("/user/id").asString()).isEqualTo(userId.toString());
    }

    @Test
    void existingEmailIsNeverLinkedAutomatically() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.client();

        ApiClient.Response callback = signIn(client, person(email));

        assertThat(callback.location()).isEqualTo("https://readowl.test/login?error=GOOGLE_ACCOUNT_EXISTS");
        assertThat(client.get(SESSION).json().at("/authenticated").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE user_id = ?", Integer.class,
                accounts.userId(email))).isZero();
    }

    @Test
    void emailNotVerifiedByGoogleIsRefused() {
        String email = uniqueEmail();
        ApiClient client = accounts.client();

        ApiClient.Response callback = signIn(client, new FakeGoogle.Person(UUID.randomUUID().toString(), email, false, "Pessoa"));

        assertThat(callback.location()).isEqualTo("https://readowl.test/login?error=GOOGLE_EMAIL_UNVERIFIED");
        assertThat(client.get(SESSION).json().at("/authenticated").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    @Test
    void suspendedAccountCannotEnterThroughGoogle() {
        String email = uniqueEmail();
        FakeGoogle.Person person = person(email);
        signIn(accounts.client(), person);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);
        ApiClient client = accounts.client();

        ApiClient.Response callback = signIn(client, person);

        assertThat(callback.location()).isEqualTo("https://readowl.test/login?error=ACCOUNT_SUSPENDED");
        assertThat(client.get(SESSION).json().at("/authenticated").asBoolean()).isFalse();
    }

    @Test
    void forgedStateIsRejected() {
        ApiClient client = accounts.client();
        client.get(START);

        ApiClient.Response callback = client.get(CALLBACK + "?code=codigo&state=forjado");

        assertThat(callback.location()).isEqualTo("https://readowl.test/login?error=GOOGLE_LOGIN_FAILED");
        assertThat(client.get(SESSION).json().at("/authenticated").asBoolean()).isFalse();
    }

    @Test
    void idTokenIssuedForAnotherClientIsRejected() {
        String email = uniqueEmail();
        ApiClient client = accounts.client();
        ApiClient.Response start = client.get(START);
        Map<String, String> parameters = UriComponentsBuilder.fromUri(URI.create(start.location())).build()
                .getQueryParams().toSingleValueMap();
        GOOGLE.authorize(person(email), parameters.get("nonce"));
        GOOGLE.issueTokensForAnotherClient();

        ApiClient.Response callback = client.get(CALLBACK + "?code=codigo&state=" + parameters.get("state"));

        assertThat(callback.location()).isEqualTo("https://readowl.test/login?error=GOOGLE_LOGIN_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    @Test
    void userCanLinkGoogleExplicitlyAfterConfirmingThePassword() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        UUID userId = accounts.userId(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);
        FakeGoogle.Person person = new FakeGoogle.Person(UUID.randomUUID().toString(), email, true, "Outro Nome");

        assertThat(client.post(LINK, body("password", PASSWORD)).status()).isEqualTo(204);
        ApiClient.Response callback = signIn(client, person);

        assertThat(callback.location()).isEqualTo("https://readowl.test/conta");
        ApiClient.Response session = client.get(SESSION);
        assertThat(session.json().at("/user/id").asString()).isEqualTo(userId.toString());
        assertThat(session.json().at("/user/googleLinked").asBoolean()).isTrue();
        assertThat(session.json().at("/user/name").asString()).isEqualTo("Pessoa Leitora");

        ApiClient viaGoogle = accounts.client();
        assertThat(signIn(viaGoogle, person).location()).isEqualTo(HOME);
        assertThat(viaGoogle.get(SESSION).json().at("/user/id").asString()).isEqualTo(userId.toString());
    }

    @Test
    void linkingRequiresTheCorrectPasswordAndALogin() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);

        ApiClient.Response wrong = client.post(LINK, body("password", "errada-123"));
        ApiClient.Response missing = client.post(LINK, body());

        assertThat(wrong.code()).isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(missing.code()).isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(accounts.client().post(LINK, body("password", PASSWORD)).status()).isEqualTo(401);
    }

    @Test
    void wrongPasswordAttemptsOnLinkingCountTowardsTheLimit() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(client.post(LINK, body("password", "errada-123")).status()).isEqualTo(400);
        }

        assertThat(client.post(LINK, body("password", PASSWORD)).status()).isEqualTo(429);
    }

    @Test
    void googleAccountOfAnotherUserCannotBeLinkedAndTheSessionSurvives() {
        FakeGoogle.Person owner = person(uniqueEmail());
        signIn(accounts.client(), owner);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        UUID userId = accounts.userId(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);

        assertThat(client.post(LINK, body("password", PASSWORD)).status()).isEqualTo(204);
        ApiClient.Response callback = signIn(client, owner);

        assertThat(callback.location()).isEqualTo("https://readowl.test/conta?error=GOOGLE_LINKED_TO_OTHER_ACCOUNT");
        assertThat(client.get(SESSION).json().at("/user/id").asString()).isEqualTo(userId.toString());
    }

    @Test
    void accountCreatedWithGoogleCannotUseThePasswordChangeButCanRecover() {
        String email = uniqueEmail();
        ApiClient client = accounts.client();
        signIn(client, person(email));

        ApiClient.Response change = client.put("/api/v1/users/me/password",
                body("currentPassword", "qualquer-123", "newPassword", "nova-senha-789"));
        assertThat(change.status()).isEqualTo(409);
        assertThat(change.code()).isEqualTo("PASSWORD_NOT_SET");

        assertThat(accounts.client().post("/api/v1/auth/password/forgot", body("email", email)).status()).isEqualTo(202);
        String token = mail.awaitToken(email);
        assertThat(accounts.client().post("/api/v1/auth/password/reset",
                body("token", token, "newPassword", "nova-senha-789")).status()).isEqualTo(204);
        assertThat(accounts.client().post("/api/v1/auth/login",
                body("email", email, "password", "nova-senha-789")).status()).isEqualTo(200);
    }

    @Test
    void passwordLoginDoesNotWorkForAGoogleOnlyAccount() {
        String email = uniqueEmail();
        signIn(accounts.client(), person(email));

        ApiClient.Response login = accounts.client().post("/api/v1/auth/login", body("email", email, "password", PASSWORD));

        assertThat(login.status()).isEqualTo(401);
        assertThat(login.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void noGeneratedLoginPageIsServed() {
        ApiClient.Response login = accounts.client().get("/login");

        assertThat(login.status()).isEqualTo(401);
        assertThat(login.body()).doesNotContain("<html");
    }
}
