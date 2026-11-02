package br.com.readowl.readowl.banner;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class BannerIntegrationTest {

    private static final String PUBLIC = "/api/v1/banners";
    private static final String ADMIN = "/api/v1/admin/banners";

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
        mail.setFailing(false);
        jdbc.update("DELETE FROM banners");
    }

    private ApiClient withRole(String role) {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = ? WHERE email = ?", role, email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private static Map<String, Object> banner(String name, int position) {
        return body("name", name, "imageUrl", "https://i.imgur.com/a.png", "linkUrl", "https://readowl.example.com/obras/a",
                "position", position, "enabled", true);
    }

    private static Map<String, Object> with(Map<String, Object> data, Object... changes) {
        Map<String, Object> copy = new LinkedHashMap<>(data);
        copy.putAll(body(changes));
        return copy;
    }

    private static String create(ApiClient admin, Map<String, Object> data) {
        ApiClient.Response created = admin.post(ADMIN, data);
        assertThat(created.status()).isEqualTo(201);
        return created.json().at("/id").asString();
    }

    private List<String> activeNames() {
        JsonNode list = accounts.client().get(PUBLIC).json();
        return list.valueStream().map(item -> item.at("/name").asString()).toList();
    }

    @Test
    void publicListNeedsNoSessionAndShowsOnlyPublicFields() {
        create(withRole("ADMIN"), banner("Destaque", 1));

        ApiClient.Response list = accounts.client().get(PUBLIC);

        assertThat(list.status()).isEqualTo(200);
        assertThat(list.setCookies()).isEmpty();
        assertThat(list.json().at("/0/name").asString()).isEqualTo("Destaque");
        assertThat(list.json().at("/0/imageUrl").asString()).startsWith("https://");
        assertThat(list.body()).doesNotContain("enabled").doesNotContain("version").doesNotContain("activeFrom");
    }

    @Test
    void onlyAdministratorsManageBanners() {
        ApiClient admin = withRole("ADMIN");
        String id = create(admin, banner("Destaque", 1));
        ApiClient anonymous = accounts.client();

        assertThat(anonymous.get(ADMIN).status()).isEqualTo(401);
        assertThat(anonymous.post(ADMIN, banner("X", 1)).status()).isEqualTo(401);
        for (String role : List.of("USER", "MODERATOR")) {
            ApiClient other = withRole(role);
            assertThat(other.get(ADMIN).status()).isEqualTo(403);
            assertThat(other.post(ADMIN, banner("X", 1)).status()).isEqualTo(403);
            assertThat(other.delete(ADMIN + "/" + id).status()).isEqualTo(403);
        }
        assertThat(withRole("ROOT").get(ADMIN).json().at("/totalElements").asLong()).isEqualTo(1);
        assertThat(admin.postWithoutCsrf(ADMIN, banner("X", 1)).status()).isEqualTo(403);
    }

    @Test
    void activeBannersFollowStatusAndPeriodInPositionOrder() {
        ApiClient admin = withRole("ADMIN");
        Instant now = Instant.now();
        String past = now.minus(1, ChronoUnit.DAYS).toString();
        String future = now.plus(1, ChronoUnit.DAYS).toString();
        create(admin, banner("Segundo", 2));
        create(admin, with(banner("Primeiro", 1), "activeFrom", past, "activeUntil", future));
        create(admin, with(banner("Desligado", 0), "enabled", false));
        create(admin, with(banner("Ainda não", 0), "activeFrom", future));
        create(admin, with(banner("Encerrado", 0), "activeUntil", past));

        assertThat(activeNames()).containsExactly("Primeiro", "Segundo");
        assertThat(admin.get(ADMIN).json().at("/totalElements").asLong()).isEqualTo(5);
    }

    @Test
    void anImageOutsideTheAllowedSitesIsRefusedButALinkCanGoAnywhere() {
        ApiClient admin = withRole("ADMIN");
        String outside = "https://site-desconhecido.example.com/a.png";

        assertThat(admin.post(ADMIN, with(banner("X", 1), "imageUrl", outside)).code()).isEqualTo("INVALID_BANNER_URL");
        assertThat(admin.post(ADMIN, with(banner("X", 1), "linkUrl", outside)).status()).isEqualTo(201);
    }

    @Test
    void addressesMustBeHttps() {
        ApiClient admin = withRole("ADMIN");
        List<String> invalid = List.of("http://cdn.example.com/a.png", "javascript:alert(1)", "data:text/html,oi",
                "//cdn.example.com/a.png", "/obras/a", "https://", "https://usuario@cdn.example.com/a", "https://a b.com");

        for (String url : invalid) {
            assertThat(admin.post(ADMIN, with(banner("X", 1), "imageUrl", url)).code()).as(url).isEqualTo("INVALID_BANNER_URL");
            assertThat(admin.post(ADMIN, with(banner("X", 1), "linkUrl", url)).code()).as(url).isEqualTo("INVALID_BANNER_URL");
        }
        assertThat(activeNames()).isEmpty();
    }

    @Test
    void fieldsAreValidated() {
        ApiClient admin = withRole("ADMIN");
        Instant now = Instant.now();

        assertThat(admin.post(ADMIN, body()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.post(ADMIN, with(banner("X", 1), "name", "  ")).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.post(ADMIN, with(banner("X", 1), "name", "a".repeat(201))).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.post(ADMIN, with(banner("X", 1), "name", "a\u0000b")).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.post(ADMIN, with(banner("X", 1), "position", -1)).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.post(ADMIN, with(banner("X", 1), "activeFrom", now.toString(), "activeUntil", now.toString())).code())
                .isEqualTo("INVALID_BANNER_PERIOD");
        assertThat(admin.post(ADMIN, with(banner("X", 1), "activeFrom", "ontem")).status()).isEqualTo(400);
    }

    @Test
    void updateReplacesEverythingAndDetectsConflicts() {
        ApiClient admin = withRole("ADMIN");
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        String id = create(admin, with(banner("Original", 1), "activeFrom", future.toString()));
        JsonNode loaded = admin.get(ADMIN + "/" + id).json();
        long version = loaded.at("/version").asLong();
        assertThat(activeNames()).isEmpty();

        ApiClient.Response updated = admin.put(ADMIN + "/" + id, with(banner("Alterado", 5), "version", version));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().at("/name").asString()).isEqualTo("Alterado");
        assertThat(updated.json().at("/position").asInt()).isEqualTo(5);
        assertThat(updated.json().at("/activeFrom").isNull()).isTrue();
        assertThat(updated.json().at("/version").asLong()).isGreaterThan(version);
        assertThat(activeNames()).containsExactly("Alterado");

        ApiClient.Response stale = admin.put(ADMIN + "/" + id, with(banner("Antigo", 9), "version", version));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("BANNER_CONFLICT");
        assertThat(stale.json().at("/currentVersion").asLong()).isEqualTo(updated.json().at("/version").asLong());
        assertThat(admin.put(ADMIN + "/" + id, banner("Sem versão", 1)).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void bannersCanBeDeleted() {
        ApiClient admin = withRole("ADMIN");
        String id = create(admin, banner("Temporário", 1));

        assertThat(admin.delete(ADMIN + "/" + id).status()).isEqualTo(204);

        assertThat(activeNames()).isEmpty();
        assertThat(admin.delete(ADMIN + "/" + id).code()).isEqualTo("BANNER_NOT_FOUND");
        assertThat(admin.get(ADMIN + "/" + id).code()).isEqualTo("BANNER_NOT_FOUND");
        assertThat(admin.put(ADMIN + "/" + UUID.randomUUID(), with(banner("X", 1), "version", 0)).code()).isEqualTo("BANNER_NOT_FOUND");
    }

    @Test
    void adminListIsPaginatedWithAllowedSorts() {
        ApiClient admin = withRole("ADMIN");
        create(admin, banner("B", 1));
        create(admin, banner("A", 2));

        assertThat(admin.get(ADMIN + "?sort=name").json().at("/content/0/name").asString()).isEqualTo("A");
        assertThat(admin.get(ADMIN).json().at("/content/0/name").asString()).isEqualTo("B");
        assertThat(admin.get(ADMIN + "?sort=imageUrl").code()).isEqualTo("INVALID_SORT");
    }

    @Test
    void changesAreAudited() {
        ApiClient admin = withRole("ADMIN");
        String id = create(admin, banner("Auditado", 1));

        assertThat(admin.delete(ADMIN + "/" + id).status()).isEqualTo(204);

        assertThat(admin.get("/api/v1/admin/audit-log?resource=" + ADMIN + "/" + id).json().at("/content/0/action").asString())
                .isEqualTo("DELETE /api/v1/admin/banners/{id}");
    }
}
