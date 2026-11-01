package br.com.readowl.readowl.auth;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.TERMS_VERSION;
import static br.com.readowl.readowl.support.AccountFixtures.registration;
import static br.com.readowl.readowl.support.AccountFixtures.unique;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AccountSecurityIntegrationTest {

    private static final String SESSIONS = "/api/v1/users/me/sessions";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME = "/api/v1/users/me";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private BookFixtures books;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
    }

    private String verified() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return email;
    }

    private void awaitMail(String to, String subjectPart) {
        assertThat(mail.awaitSubject(to, subjectPart).getText()).doesNotContain("#token=");
    }

    @Test
    void sessionsCanBeListedWithoutShowingTheirIdsAndEndedOneByOne() {
        String email = verified();
        ApiClient here = accounts.loggedIn(email, PASSWORD);
        ApiClient there = accounts.loggedIn(email, PASSWORD);

        JsonNode list = here.get(SESSIONS).json();

        assertThat(list).hasSize(2);
        assertThat(list.findValuesAsString("id")).doesNotContain(here.cookie("SESSION").orElseThrow(), there.cookie("SESSION").orElseThrow());
        String other = null;
        for (JsonNode session : list) {
            if (!session.at("/current").asBoolean()) {
                other = session.at("/id").asString();
            }
        }
        assertThat(other).isNotNull();

        assertThat(here.delete(SESSIONS + "/" + other).status()).isEqualTo(204);

        assertThat(there.get(ME).status()).isEqualTo(401);
        assertThat(here.get(ME).status()).isEqualTo(200);
    }

    @Test
    void endingOtherSessionsKeepsTheCurrentOne() {
        String email = verified();
        ApiClient here = accounts.loggedIn(email, PASSWORD);
        ApiClient there = accounts.loggedIn(email, PASSWORD);

        assertThat(here.delete(SESSIONS).status()).isEqualTo(204);

        assertThat(there.get(ME).status()).isEqualTo(401);
        assertThat(here.get(ME).status()).isEqualTo(200);
    }

    @Test
    void endingTheCurrentSessionLogsOut() {
        ApiClient here = accounts.loggedIn(verified(), PASSWORD);
        String id = here.get(SESSIONS).json().get(0).at("/id").asString();

        assertThat(here.delete(SESSIONS + "/" + id).status()).isEqualTo(204);

        assertThat(here.get(ME).status()).isEqualTo(401);
    }

    @Test
    void aSessionOfAnotherAccountCannotBeEnded() {
        ApiClient victim = accounts.loggedIn(verified(), PASSWORD);
        String id = victim.get(SESSIONS).json().get(0).at("/id").asString();

        var refused = accounts.loggedIn(verified(), PASSWORD).delete(SESSIONS + "/" + id);

        assertThat(refused.status()).isEqualTo(404);
        assertThat(victim.get(ME).status()).isEqualTo(200);
    }

    @Test
    void aSessionInUseStillEndsAtTheMaximumAge() {
        String email = verified();
        ApiClient client = accounts.loggedIn(email, PASSWORD);
        assertThat(client.get(ME).status()).isEqualTo(200);

        jdbc.update("UPDATE spring_session SET creation_time = ? WHERE principal_name = ?",
                System.currentTimeMillis() - 31L * 24 * 3600 * 1000, accounts.userId(email).toString());

        assertThat(client.get(ME).status()).isEqualTo(401);
    }

    @Test
    void aPrivilegedSessionHasShorterLimits() {
        String email = verified();
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE email = ?", email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);
        String principal = accounts.userId(email).toString();
        assertThat(jdbc.queryForObject("SELECT max_inactive_interval FROM spring_session WHERE principal_name = ?", Integer.class, principal))
                .isEqualTo(3600);

        jdbc.update("UPDATE spring_session SET creation_time = ? WHERE principal_name = ?",
                System.currentTimeMillis() - 13L * 3600 * 1000, principal);

        assertThat(client.get(ME).status()).isEqualTo(401);
    }

    @Test
    void rememberMeDecidesTheCookieAndTheIdleLimit() {
        String email = verified();
        ApiClient regular = accounts.client();
        var login = regular.post(LOGIN, body("email", email, "password", PASSWORD));
        assertThat(login.setCookie("SESSION").orElseThrow()).doesNotContainIgnoringCase("Max-Age");

        ApiClient remembered = accounts.client();
        var kept = remembered.post(LOGIN, body("email", email, "password", PASSWORD, "rememberMe", true));
        assertThat(kept.setCookie("SESSION").orElseThrow()).containsIgnoringCase("Max-Age");

        assertThat(jdbc.queryForList("SELECT max_inactive_interval FROM spring_session WHERE principal_name = ?", Integer.class,
                accounts.userId(email).toString())).containsExactlyInAnyOrder(12 * 3600, 14 * 24 * 3600);
    }

    @Test
    void aPrivilegedAccountIsNeverRemembered() {
        String email = verified();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);

        var login = accounts.client().post(LOGIN, body("email", email, "password", PASSWORD, "rememberMe", true));

        assertThat(login.setCookie("SESSION").orElseThrow()).doesNotContainIgnoringCase("Max-Age");
    }

    @Test
    void changingARoleRequiresARecentLogin() {
        String rootEmail = verified();
        jdbc.update("UPDATE users SET role = 'ROOT' WHERE email = ?", rootEmail);
        ApiClient root = accounts.loggedIn(rootEmail, PASSWORD);
        UUID target = accounts.userId(verified());
        byte[] old = serialized(System.currentTimeMillis() / 1000 - 3600);
        jdbc.update("""
                UPDATE spring_session_attributes SET attribute_bytes = ?
                WHERE attribute_name = 'readowl.authenticatedAt'
                  AND session_primary_id IN (SELECT primary_id FROM spring_session WHERE principal_name = ?)
                """, old, accounts.userId(rootEmail).toString());

        var refused = root.patch("/api/v1/admin/users/" + target, body("role", "MODERATOR"));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("RECENT_LOGIN_REQUIRED");
        assertThat(root.patch("/api/v1/admin/users/" + target, body("description", "Só o perfil")).status()).isEqualTo(200);

        ApiClient again = accounts.loggedIn(rootEmail, PASSWORD);
        assertThat(again.patch("/api/v1/admin/users/" + target, body("role", "MODERATOR")).status()).isEqualTo(200);
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
    void passwordChangeAndResetWarnTheOwner() {
        String email = verified();
        ApiClient client = accounts.loggedIn(email, PASSWORD);

        assertThat(client.put("/api/v1/users/me/password", body("currentPassword", PASSWORD, "newPassword", "nova-senha-456")).status())
                .isEqualTo(204);

        awaitMail(email, "senha do Readowl foi alterada");
    }

    @Test
    void theEmailChangeOnlyHappensWithALinkSentToTheNewAddress() {
        String email = verified();
        ApiClient client = accounts.loggedIn(email, PASSWORD);
        ApiClient other = accounts.loggedIn(email, PASSWORD);
        String newEmail = uniqueEmail();

        assertThat(client.post("/api/v1/users/me/email", body("newEmail", newEmail, "password", PASSWORD)).status()).isEqualTo(202);
        String token = mail.awaitToken(newEmail);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isOne();
        assertThat(accounts.client().post("/api/v1/auth/email/confirm", body("token", token)).status()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, newEmail)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
        awaitMail(email, "e-mail da sua conta do Readowl foi alterado");
        assertThat(client.get(ME).status()).isEqualTo(401);
        assertThat(other.get(ME).status()).isEqualTo(401);
        accounts.loggedIn(newEmail, PASSWORD);
        assertThat(accounts.client().post("/api/v1/auth/email/confirm", body("token", token)).code()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void theWrongPasswordDoesNotStartAnEmailChange() {
        ApiClient client = accounts.loggedIn(verified(), PASSWORD);

        var refused = client.post("/api/v1/users/me/email", body("newEmail", uniqueEmail(), "password", "errada-123"));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("INVALID_CURRENT_PASSWORD");
    }

    @Test
    void askingForAnAddressInUseAnswersTheSameAndWarnsItsOwner() {
        String owner = verified();
        String email = verified();

        var response = accounts.loggedIn(email, PASSWORD).post("/api/v1/users/me/email", body("newEmail", owner, "password", PASSWORD));

        assertThat(response.status()).isEqualTo(202);
        awaitMail(owner, "Tentativa de cadastro");
    }

    @Test
    void anAddressTakenBeforeTheConfirmationIsRefused() {
        String email = verified();
        String newEmail = uniqueEmail();
        assertThat(accounts.loggedIn(email, PASSWORD).post("/api/v1/users/me/email", body("newEmail", newEmail, "password", PASSWORD)).status()).isEqualTo(202);
        String token = mail.awaitToken(newEmail);
        accounts.registerUnverified(newEmail);

        var refused = accounts.client().post("/api/v1/auth/email/confirm", body("token", token));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void registerRequiresTheCurrentTermsAndTheAgeDeclaration(CapturedOutput output) {
        var outdated = accounts.client().post("/api/v1/auth/register", body("name", "P", "username", unique(), "email", uniqueEmail(),
                "password", PASSWORD, "termsVersion", "2020-01", "ageConfirmed", true));
        assertThat(outdated.status()).isEqualTo(409);
        assertThat(outdated.code()).isEqualTo("TERMS_OUTDATED");
        assertThat(outdated.json().at("/currentVersion").asString()).isEqualTo(TERMS_VERSION);

        var noAge = accounts.client().post("/api/v1/auth/register", body("name", "P", "username", unique(), "email", uniqueEmail(),
                "password", PASSWORD, "termsVersion", TERMS_VERSION, "ageConfirmed", false));
        assertThat(noAge.status()).isEqualTo(400);

        String email = uniqueEmail();
        assertThat(accounts.client().post("/api/v1/auth/register", registration(unique(), email, PASSWORD)).status()).isEqualTo(202);
        assertThat(jdbc.queryForMap("SELECT terms_version, terms_accepted_at FROM users WHERE email = ?", email))
                .containsEntry("terms_version", TERMS_VERSION).extractingByKey("terms_accepted_at").isNotNull();
    }

    @Test
    void theTermsAreReadablePublicly() {
        JsonNode terms = accounts.client().get("/api/v1/auth/terms").json();

        assertThat(terms.at("/version").asString()).isEqualTo(TERMS_VERSION);
        assertThat(terms.at("/minimumAge").asInt()).isEqualTo(18);
    }

    @Test
    void withoutAcceptingTheCurrentTermsTheAccountOnlyLooksAfterItself() {
        String email = verified();
        ApiClient client = accounts.loggedIn(email, PASSWORD);
        jdbc.update("UPDATE users SET terms_version = '2020-01' WHERE email = ?", email);

        assertThat(client.get("/api/v1/auth/session").json().at("/user/termsAccepted").asBoolean()).isFalse();
        var refused = client.post("/api/v1/books", books.newBook("Bloqueada", BookFixtures.uniqueAcronym()));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("TERMS_NOT_ACCEPTED");
        assertThat(client.patch(ME, body("description", "x")).code()).isEqualTo("TERMS_NOT_ACCEPTED");
        assertThat(client.get(ME).status()).isEqualTo(200);
        assertThat(client.delete(SESSIONS).status()).isEqualTo(204);

        assertThat(client.post("/api/v1/users/me/terms", body("termsVersion", "2020-01", "ageConfirmed", true)).code()).isEqualTo("TERMS_OUTDATED");
        assertThat(client.post("/api/v1/users/me/terms", body("termsVersion", TERMS_VERSION, "ageConfirmed", false)).status()).isEqualTo(400);
        assertThat(client.post("/api/v1/users/me/terms", body("termsVersion", TERMS_VERSION, "ageConfirmed", true)).status()).isEqualTo(204);

        assertThat(client.get("/api/v1/auth/session").json().at("/user/termsAccepted").asBoolean()).isTrue();
        assertThat(client.post("/api/v1/books", books.newBook("Liberada", BookFixtures.uniqueAcronym())).status()).isEqualTo(201);
    }

    @Test
    void securityEventsLeaveARecordWithoutSecrets(CapturedOutput output) {
        String email = verified();
        UUID id = accounts.userId(email);

        accounts.client().post(LOGIN, body("email", email, "password", "tentativa-errada-1"));
        accounts.loggedIn(email, PASSWORD);

        assertThat(jdbc.queryForList("SELECT action FROM access_records WHERE user_id = ?", String.class, id)).contains("LOGIN_SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT ip FROM access_records WHERE user_id = ? LIMIT 1", String.class, id)).isNotBlank();
        assertThat(output).contains("evento=LOGIN_FAILED").contains("evento=LOGIN_SUCCEEDED")
                .doesNotContain("tentativa-errada-1").doesNotContain(PASSWORD);
        assertThat(output.toString().lines().filter(line -> line.contains("Evento de seguranca"))).noneMatch(line -> line.contains(email));
    }

    @Test
    void writesAreLinkedToAConnectionButDraftSavesAreNot() {
        String email = verified();
        UUID id = accounts.userId(email);
        ApiClient author = accounts.loggedIn(email, PASSWORD);
        String book = books.createBook(author);
        String chapter = books.createChapter(author, book, "Capítulo");
        books.writeDraft(author, chapter, "Rascunho");
        assertThat(author.post("/api/v1/books", body()).status()).isEqualTo(400);

        var actions = jdbc.queryForList("SELECT action FROM access_records WHERE user_id = ?", String.class, id);

        assertThat(actions).contains("POST /api/v1/books").contains("POST /api/v1/books/" + book + "/chapters");
        assertThat(actions).noneMatch(action -> action.endsWith("/draft"));
    }

    @Test
    void onlyRootSeesTheAccessRecordsAndTheReadIsAudited() {
        String email = verified();
        UUID target = accounts.userId(email);
        accounts.loggedIn(email, PASSWORD);
        String adminEmail = verified();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", adminEmail);
        String rootEmail = verified();
        jdbc.update("UPDATE users SET role = 'ROOT' WHERE email = ?", rootEmail);
        String path = "/api/v1/admin/access-records?userId=" + target;

        assertThat(accounts.client().get(path).status()).isEqualTo(401);
        assertThat(accounts.loggedIn(email, PASSWORD).get(path).status()).isEqualTo(403);
        assertThat(accounts.loggedIn(adminEmail, PASSWORD).get(path).status()).isEqualTo(403);
        var response = accounts.loggedIn(rootEmail, PASSWORD).get(path);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().at("/totalElements").asLong()).isPositive();
        assertThat(response.json().at("/content/0/ip").asString()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action LIKE 'GET /api/v1/admin/access-records%'",
                Integer.class, accounts.userId(rootEmail))).isOne();
    }

    @Test
    void theExportCarriesThePersonsDataAndNoSecrets() {
        String email = verified();
        ApiClient author = accounts.loggedIn(email, PASSWORD);
        String book = books.publishedBook(author);
        assertThat(author.post("/api/v1/books/" + book + "/comments", body("content", "Meu comentário")).status()).isEqualTo(201);

        var response = author.get("/api/v1/users/me/export");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("readowl-dados.json");
        JsonNode export = JsonMapper.builder().build().readTree(response.body());
        assertThat(export.at("/profile/email").asString()).isEqualTo(email);
        assertThat(export.at("/comments/0/content").asString()).isEqualTo("Meu comentário");
        assertThat(export.at("/books/0/id").asString()).isEqualTo(book);
        assertThat(export.at("/books/0/chapters/0/published/type").asString()).isEqualTo("doc");
        assertThat(export.at("/accessRecords").isEmpty()).isFalse();
        assertThat(response.body()).doesNotContain("password_hash").doesNotContain("token_hash").doesNotContain("argon2");
    }

    @Test
    void theExportIsLimitedPerDay() {
        ApiClient client = accounts.loggedIn(verified(), PASSWORD);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(client.get("/api/v1/users/me/export").status()).isEqualTo(200);
        }

        assertThat(client.get("/api/v1/users/me/export").status()).isEqualTo(429);
    }

    @Test
    void anAdultBookIsHiddenFromVisitorsEverywhereAndShownToAccounts() {
        ApiClient author = accounts.loggedIn(verified(), PASSWORD);
        String book = books.publishedBook(author);
        assertThat(author.patch("/api/v1/books/" + book, body("contentRating", "ADULT")).status()).isEqualTo(200);
        JsonNode details = author.get("/api/v1/books/" + book + "/details").json();
        assertThat(details.at("/contentRating").asString()).isEqualTo("ADULT");
        String slug = details.at("/slug").asString();
        String catalog = "/api/v1/books?author=" + details.at("/authorUsername").asString();

        ApiClient visitor = accounts.client();
        assertThat(visitor.get("/api/v1/books/" + slug).status()).isEqualTo(404);
        assertThat(visitor.get("/api/v1/books/" + slug + "/chapters").status()).isEqualTo(404);
        assertThat(visitor.get("/api/v1/books/" + book + "/comments").status()).isEqualTo(404);
        assertThat(visitor.get("/api/v1/books/" + book + "/rating").status()).isEqualTo(404);
        assertThat(visitor.get(catalog).json().at("/totalElements").asLong()).isZero();

        ApiClient reader = accounts.loggedIn(verified(), PASSWORD);
        assertThat(reader.get("/api/v1/books/" + slug).json().at("/contentRating").asString()).isEqualTo("ADULT");
        assertThat(reader.get(catalog).json().at("/totalElements").asLong()).isOne();
    }

    @Test
    void whatAnAccountSeesIsNeverStoredInPublicCaches() {
        ApiClient reader = accounts.loggedIn(verified(), PASSWORD);

        var logged = reader.get("/api/v1/books");
        var visitor = accounts.client().get("/api/v1/books");

        assertThat(logged.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store").doesNotContain("public");
        assertThat(visitor.headers().firstValue("Cache-Control").orElseThrow()).contains("public");
        assertThat(visitor.headers().allValues("Vary")).anyMatch(value -> value.contains("Cookie"));
    }
}
