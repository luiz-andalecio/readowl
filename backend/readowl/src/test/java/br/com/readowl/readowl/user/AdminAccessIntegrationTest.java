package br.com.readowl.readowl.user;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class AdminAccessIntegrationTest {

    private static final String USERS = "/api/v1/admin/users/";
    private static final String ME = "/api/v1/users/me";

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
    }

    private record Account(String email, UUID id, ApiClient client) {
    }

    private Account account(String role) {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = ? WHERE email = ?", role, email);
        return new Account(email, accounts.userId(email), accounts.loggedIn(email, PASSWORD));
    }

    /**
     * Outros testes criam contas root. Aqui, a conta informada precisa ser a única ativa.
     */
    private void makeOnlyRoot(UUID id) {
        jdbc.update("UPDATE users SET role = 'USER' WHERE role = 'ROOT' AND id <> ?", id);
    }

    @Test
    void onlyRootCanEditUsers() {
        Account target = account("USER");

        assertThat(account("USER").client().patch(USERS + target.id(), body("name", "X")).status()).isEqualTo(403);
        assertThat(account("MODERATOR").client().patch(USERS + target.id(), body("name", "X")).status()).isEqualTo(403);
        assertThat(account("ADMIN").client().patch(USERS + target.id(), body("name", "X")).status()).isEqualTo(403);
        assertThat(accounts.client().patch(USERS + target.id(), body("name", "X")).status()).isEqualTo(401);

        ApiClient.Response edited = account("ROOT").client().patch(USERS + target.id(),
                body("name", "Nome Novo", "description", "Editado", "username", "editado-" + target.id().toString().substring(0, 8)));

        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.json().at("/name").asString()).isEqualTo("Nome Novo");
        assertThat(edited.json().at("/description").asString()).isEqualTo("Editado");
        assertThat(target.client().get(ME).json().at("/name").asString()).isEqualTo("Nome Novo");
    }

    @Test
    void adminCanViewUsersAndUsersCannot() {
        Account target = account("USER");
        Account admin = account("ADMIN");

        assertThat(admin.client().get(USERS + target.id()).json().at("/email").asString()).isEqualTo(target.email());
        assertThat(admin.client().get("/api/v1/admin/users?size=5&sort=-createdAt").json().at("/content").size())
                .isGreaterThan(0);
        assertThat(target.client().get(USERS + admin.id()).status()).isEqualTo(403);
        assertThat(account("ROOT").client().get(USERS + UUID.randomUUID()).code()).isEqualTo("USER_NOT_FOUND");
        assertThat(admin.client().get(USERS + target.id()).body()).doesNotContain("argon2").doesNotContain("passwordHash");
    }

    @Test
    void rootCannotChangeEmailOrPasswordThroughThisEndpoint() {
        Account target = account("USER");

        ApiClient.Response response = account("ROOT").client().patch(USERS + target.id(),
                body("email", "outro@example.com", "password", "senha-trocada-1", "passwordHash", "x"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().at("/email").asString()).isEqualTo(target.email());
        assertThat(accounts.client().post("/api/v1/auth/login", body("email", target.email(), "password", PASSWORD)).status())
                .isEqualTo(200);
    }

    @Test
    void changingTheRoleAppliesImmediatelyByRevokingSessions() {
        Account target = account("USER");
        assertThat(target.client().get("/api/v1/admin/books").status()).isEqualTo(403);

        ApiClient.Response promoted = account("ROOT").client().patch(USERS + target.id(), body("role", "ADMIN"));

        assertThat(promoted.json().at("/role").asString()).isEqualTo("ADMIN");
        assertThat(target.client().get(ME).status()).isEqualTo(401);
        ApiClient again = accounts.loggedIn(target.email(), PASSWORD);
        assertThat(again.get("/api/v1/admin/books").status()).isEqualTo(200);
        assertThat(again.get("/api/v1/auth/session").json().at("/user/role").asString()).isEqualTo("ADMIN");
    }

    @Test
    void demotedAdminLosesAccessAtOnce() {
        Account admin = account("ADMIN");
        assertThat(admin.client().get("/api/v1/admin/books").status()).isEqualTo(200);

        account("ROOT").client().patch(USERS + admin.id(), body("role", "USER"));

        assertThat(admin.client().get("/api/v1/admin/books").status()).isEqualTo(401);
    }

    @Test
    void suspendingRevokesSessionsAndBlocksLogin() {
        Account target = account("USER");

        account("ROOT").client().patch(USERS + target.id(), body("status", "SUSPENDED"));

        assertThat(target.client().get(ME).status()).isEqualTo(401);
        assertThat(accounts.client().post("/api/v1/auth/login", body("email", target.email(), "password", PASSWORD)).code())
                .isEqualTo("ACCOUNT_SUSPENDED");
    }

    @Test
    void editingOnlyTheNameKeepsTheSessionsOpen() {
        Account target = account("USER");

        account("ROOT").client().patch(USERS + target.id(), body("name", "Sem Revogar"));

        assertThat(target.client().get(ME).status()).isEqualTo(200);
    }

    @Test
    void lastActiveRootCannotBeRemoved() {
        Account root = account("ROOT");
        makeOnlyRoot(root.id());

        ApiClient.Response demoted = root.client().patch(USERS + root.id(), body("role", "ADMIN"));
        ApiClient.Response suspended = root.client().patch(USERS + root.id(), body("status", "SUSPENDED"));

        assertThat(demoted.status()).isEqualTo(409);
        assertThat(demoted.code()).isEqualTo("LAST_ROOT");
        assertThat(suspended.code()).isEqualTo("LAST_ROOT");
        assertThat(root.client().get(ME).status()).isEqualTo(200);
    }

    @Test
    void twoRootsRemovingEachOtherAtTheSameTimeLeaveOneActive() {
        Account first = account("ROOT");
        Account second = account("ROOT");
        makeOnlyRoot(first.id());
        jdbc.update("UPDATE users SET role = 'ROOT' WHERE id = ?", second.id());

        List<CompletableFuture<ApiClient.Response>> requests = List.of(
                CompletableFuture.supplyAsync(() -> first.client().patch(USERS + second.id(), body("role", "USER"))),
                CompletableFuture.supplyAsync(() -> second.client().patch(USERS + first.id(), body("role", "USER"))));
        requests.forEach(CompletableFuture::join);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ROOT' AND status = 'ACTIVE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void takenAndReservedUsernamesAreRefused() {
        Account target = account("USER");
        String taken = account("USER").client().get(ME).json().at("/username").asString();
        ApiClient root = account("ROOT").client();

        assertThat(root.patch(USERS + target.id(), body("username", taken)).code()).isEqualTo("USERNAME_TAKEN");
        assertThat(root.patch(USERS + target.id(), body("username", "root")).code()).isEqualTo("USERNAME_TAKEN");
        assertThat(root.patch(USERS + target.id(), body("username", "x")).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void usersCannotPromoteThemselves() {
        Account user = account("USER");

        assertThat(user.client().patch(ME, body("role", "ROOT")).json().at("/role").asString()).isEqualTo("USER");
        assertThat(user.client().patch(USERS + user.id(), body("role", "ROOT")).status()).isEqualTo(403);
    }

    @Test
    void adminAndRootManageGenresAndTags() {
        String name = "Gênero " + UUID.randomUUID().toString().substring(0, 8);
        ApiClient admin = account("ADMIN").client();

        ApiClient.Response created = admin.post("/api/v1/admin/genres", body("name", name));
        assertThat(created.status()).isEqualTo(201);
        String id = created.json().at("/id").asString();
        assertThat(admin.post("/api/v1/admin/genres", body("name", name.toUpperCase())).code()).isEqualTo("GENRE_NAME_TAKEN");

        ApiClient root = account("ROOT").client();
        ApiClient.Response renamed = root.patch("/api/v1/admin/genres/" + id, body("name", name + " Novo"));
        assertThat(renamed.json().at("/name").asString()).isEqualTo(name + " Novo");
        assertThat(root.delete("/api/v1/admin/genres/" + id).status()).isEqualTo(204);
        assertThat(root.delete("/api/v1/admin/genres/" + id).code()).isEqualTo("GENRE_NOT_FOUND");

        String tagId = admin.post("/api/v1/admin/tags", body("name", name)).json().at("/id").asString();
        assertThat(root.patch("/api/v1/admin/tags/" + tagId, body("name", name + " B")).status()).isEqualTo(200);
        assertThat(root.delete("/api/v1/admin/tags/" + tagId).status()).isEqualTo(204);
    }

    @Test
    void genreInUseCannotBeDeletedAndRegularUsersCannotManageThem() {
        ApiClient user = account("USER").client();
        assertThat(user.post("/api/v1/admin/genres", body("name", "Invasor")).status()).isEqualTo(403);
        assertThat(account("MODERATOR").client().post("/api/v1/admin/tags", body("name", "Invasor")).status()).isEqualTo(403);

        UUID genre = jdbc.queryForObject("SELECT id FROM genres WHERE name = 'Terror'", UUID.class);
        String acronym = "G" + UUID.randomUUID().toString().substring(0, 8).toUpperCase().replace("-", "");
        assertThat(user.post("/api/v1/books", body("title", "Em uso", "acronym", acronym, "synopsis", "S",
                "genreIds", List.of(genre))).status()).isEqualTo(201);

        ApiClient.Response response = account("ADMIN").client().delete("/api/v1/admin/genres/" + genre);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("GENRE_IN_USE");
    }
}
