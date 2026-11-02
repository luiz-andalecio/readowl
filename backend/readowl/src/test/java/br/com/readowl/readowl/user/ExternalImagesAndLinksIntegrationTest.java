package br.com.readowl.readowl.user;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import br.com.readowl.readowl.support.RecordingObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class ExternalImagesAndLinksIntegrationTest {

    private static final String AVATAR = "/api/v1/users/me/avatar";
    private static final String LINKS = "/api/v1/users/me/links";
    private static final String COVER = "https://i.imgur.com/abc123.jpg";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private RecordingObjectStorage storage;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private BookFixtures books;
    private String email;
    private ApiClient person;
    private ApiClient visitor;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
        storage.setFailing(false);
        email = uniqueEmail();
        accounts.registerVerified(email);
        person = accounts.loggedIn(email, PASSWORD);
        visitor = accounts.client();
    }

    private static byte[] png(int side) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB), "png", bytes);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return bytes.toByteArray();
    }

    private String keyOf(String url) {
        return url.substring("https://cdn.readowl.test/".length());
    }

    private String upload() {
        ApiClient.Response response = person.putBytes(AVATAR, png(300), "image/png");
        assertThat(response.status()).isEqualTo(200);
        return response.json().at("/avatarUrl").asString();
    }

    private String username() {
        return person.get("/api/v1/users/me").json().at("/username").asString();
    }

    @Test
    void aPictureAppearsInTheProfileAndAtTheAddressReturned() {
        String url = upload();

        assertThat(url).startsWith("https://cdn.readowl.test/avatars/").endsWith(".jpg");
        assertThat(storage.get(keyOf(url))).isNotEmpty();
        assertThat(person.get("/api/v1/users/me").json().at("/avatarUrl").asString()).isEqualTo(url);
        assertThat(visitor.get("/api/v1/users/" + username()).json().at("/avatarUrl").asString()).isEqualTo(url);
        assertThat(person.get("/api/v1/auth/session").json().at("/user/avatarUrl").asString()).isEqualTo(url);
    }

    @Test
    void theNameOfTheFileNeverComesFromTheClientAndIsNotReused() {
        assertThat(upload()).isNotEqualTo(upload());
    }

    @Test
    void replacingThePictureDeletesThePreviousFile() {
        String first = upload();

        String second = upload();

        assertThat(storage.holds(keyOf(first))).isFalse();
        assertThat(storage.holds(keyOf(second))).isTrue();
    }

    @Test
    void removingThePictureDeletesTheFile() {
        String url = upload();

        assertThat(person.delete(AVATAR).status()).isEqualTo(204);

        assertThat(storage.holds(keyOf(url))).isFalse();
        assertThat(person.get("/api/v1/users/me").json().at("/avatarUrl").isNull()).isTrue();
    }

    @Test
    void deletingTheAccountDeletesThePictureAndTheLinks() {
        String url = upload();
        assertThat(person.put(LINKS, body("links", List.of(link("WEBSITE", "https://exemplo.com")))).status()).isEqualTo(200);
        UUID id = accounts.userId(email);

        assertThat(person.delete("/api/v1/users/me", body("password", PASSWORD)).status()).isEqualTo(204);

        assertThat(storage.holds(keyOf(url))).isFalse();
        assertThat(jdbc.queryForMap("SELECT avatar_key, avatar_url FROM users WHERE id = ?", id))
                .containsEntry("avatar_key", null).containsEntry("avatar_url", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_links WHERE user_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void aHostedImageCanBeThePicture() {
        assertThat(person.put(AVATAR, body("url", " " + COVER + " ")).json().at("/avatarUrl").asString()).isEqualTo(COVER);

        assertThat(visitor.get("/api/v1/users/" + username()).json().at("/avatarUrl").asString()).isEqualTo(COVER);
        assertThat(person.delete(AVATAR).status()).isEqualTo(204);
        assertThat(person.get("/api/v1/users/me").json().at("/avatarUrl").isNull()).isTrue();
    }

    @Test
    void switchingFromAnUploadedPictureToAHostedOneDeletesTheFileAndBack() {
        String uploaded = upload();

        assertThat(person.put(AVATAR, body("url", COVER)).status()).isEqualTo(200);
        assertThat(storage.holds(keyOf(uploaded))).isFalse();

        String again = upload();
        assertThat(storage.holds(keyOf(again))).isTrue();
        assertThat(person.get("/api/v1/users/me").json().at("/avatarUrl").asString()).isEqualTo(again);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://site-desconhecido.example.com/a.jpg", "http://i.imgur.com/a.jpg", "javascript:alert(1)",
            "https://i.imgur.com.golpe.example.com/a.jpg"})
    void aHostedPictureOutsideTheAllowedSitesIsRefusedAndKeepsTheCurrentOne(String url) {
        String current = upload();

        ApiClient.Response refused = person.put(AVATAR, body("url", url));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("INVALID_AVATAR_URL");
        assertThat(person.get("/api/v1/users/me").json().at("/avatarUrl").asString()).isEqualTo(current);
        assertThat(storage.holds(keyOf(current))).isTrue();
    }

    @Test
    void moderationRemovesAHostedPicture() {
        assertThat(person.put(AVATAR, body("url", COVER)).status()).isEqualTo(200);
        UUID id = accounts.userId(email);
        String moderatorEmail = uniqueEmail();
        accounts.registerVerified(moderatorEmail);
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE email = ?", moderatorEmail);

        assertThat(accounts.loggedIn(moderatorEmail, PASSWORD).post("/api/v1/moderation/users/" + id + "/remove-avatar", body("reason", "Imagem imprópria")).status()).isEqualTo(204);

        assertThat(person.get("/api/v1/users/me").json().at("/avatarUrl").isNull()).isTrue();
    }

    @Test
    void aPictureAboveTheLimitOrAFakeOneIsRefusedWithoutStoringAnything() {
        assertThat(person.putBytes(AVATAR, png(301), "image/png").code()).isEqualTo("AVATAR_TOO_LARGE");
        assertThat(person.putBytes(AVATAR, "<script>alert(1)</script>".getBytes(), "image/png").code()).isEqualTo("INVALID_IMAGE");
        assertThat(person.putBytes(AVATAR, new byte[2 * 1024 * 1024 + 1], "image/png").code()).isEqualTo("AVATAR_FILE_TOO_LARGE");
        assertThat(person.putBytes(AVATAR, png(100), "image/svg+xml").status()).isEqualTo(415);
        assertThat(person.get("/api/v1/users/me").json().at("/avatarUrl").isNull()).isTrue();
    }

    @Test
    void aStorageFailureAnswers503AndKeepsTheCurrentPicture() {
        String url = upload();
        storage.setFailing(true);

        ApiClient.Response failed = person.putBytes(AVATAR, png(100), "image/png");

        assertThat(failed.status()).isEqualTo(503);
        assertThat(failed.code()).isEqualTo("AVATARS_UNAVAILABLE");
        assertThat(person.get("/api/v1/users/me").json().at("/avatarUrl").asString()).isEqualTo(url);
        assertThat(storage.holds(keyOf(url))).isTrue();
    }

    @Test
    void aHostedPictureStillWorksWhenTheStorageIsDown() {
        storage.setFailing(true);

        assertThat(person.put(AVATAR, body("url", COVER)).json().at("/avatarUrl").asString()).isEqualTo(COVER);

        assertThat(person.get("/api/v1/users/me").json().at("/avatarUrl").asString()).isEqualTo(COVER);
    }

    @Test
    void uploadingRequiresLoginCsrfAndHasALimit() {
        assertThat(visitor.putBytes(AVATAR, png(100), "image/png").status()).isEqualTo(401);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(person.putBytes(AVATAR, png(50), "image/png").status()).isEqualTo(200);
        }

        assertThat(person.putBytes(AVATAR, png(50), "image/png").status()).isEqualTo(429);
    }

    @Test
    void moderationRemovesOnlyThePictureAndWarnsTheOwner() {
        String url = upload();
        UUID id = accounts.userId(email);
        String moderatorEmail = uniqueEmail();
        accounts.registerVerified(moderatorEmail);
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE email = ?", moderatorEmail);
        ApiClient moderator = accounts.loggedIn(moderatorEmail, PASSWORD);
        String remove = "/api/v1/moderation/users/" + id + "/remove-avatar";

        assertThat(person.post(remove, body("reason", "Tentando")).status()).isEqualTo(403);
        assertThat(moderator.post(remove, body("reason", "Imagem imprópria")).status()).isEqualTo(204);

        assertThat(storage.holds(keyOf(url))).isFalse();
        JsonNode notice = person.get("/api/v1/notifications").json().at("/content/0");
        assertThat(notice.at("/type").asString()).isEqualTo("CONTENT_HIDDEN");
        assertThat(notice.at("/message").asString()).isEqualTo("Imagem imprópria");
        assertThat(visitor.get("/api/v1/users/" + username()).status()).isEqualTo(200);
        assertThat(moderator.post(remove, body("reason", "De novo")).code()).isEqualTo("NO_AVATAR");
    }

    private static Map<String, Object> link(String type, String url) {
        return body("type", type, "url", url);
    }

    @Test
    void linksAppearInThePublicProfileInTheOrderSent() {
        ApiClient.Response saved = person.put(LINKS, body("links", List.of(
                link("APOIA_SE", "https://apoia.se/maria"),
                link("DISCORD", "https://discord.gg/abc"),
                link("WEBSITE", "https://meu-blog.example.com"))));

        assertThat(saved.status()).isEqualTo(200);
        JsonNode links = visitor.get("/api/v1/users/" + username()).json().at("/links");
        assertThat(links.at("/0/type").asString()).isEqualTo("APOIA_SE");
        assertThat(links.at("/1/url").asString()).isEqualTo("https://discord.gg/abc");
        assertThat(links.at("/2/type").asString()).isEqualTo("WEBSITE");

        assertThat(person.put(LINKS, body("links", List.of())).status()).isEqualTo(200);
        assertThat(visitor.get("/api/v1/users/" + username()).json().at("/links")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "APOIA_SE, https://apoia.se.golpe.example.com/maria",
            "APOIA_SE, https://golpeapoia.se/maria",
            "DISCORD, https://discord.golpe.example.com/abc",
            "INSTAGRAM, http://instagram.com/maria",
            "WEBSITE, javascript:alert(1)",
            "WEBSITE, http://site.example.com",
            "WEBSITE, https://usuario:senha@site.example.com",
            "WEBSITE, https://192.168.0.1/painel",
            "WEBSITE, https://localhost/painel"
    })
    void aLinkOutsideTheDomainOfItsTypeIsRefused(String type, String url) {
        ApiClient.Response refused = person.put(LINKS, body("links", List.of(link(type, url))));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("INVALID_LINK");
    }

    @Test
    void subdomainsOfTheRightSiteAreAcceptedAndTheListHasALimit() {
        assertThat(person.put(LINKS, body("links", List.of(link("YOUTUBE", "https://www.youtube.com/@maria")))).status()).isEqualTo(200);

        List<Map<String, Object>> tooMany = java.util.stream.IntStream.range(0, 11)
                .mapToObj(n -> link("WEBSITE", "https://site" + n + ".example.com")).toList();
        assertThat(person.put(LINKS, body("links", tooMany)).status()).isEqualTo(400);
    }

    @Test
    void aBookShowsTheCoverEverywhere() {
        Map<String, Object> data = new LinkedHashMap<>(books.newBook("Obra com capa", BookFixtures.uniqueAcronym()));
        data.put("coverUrl", COVER);
        String book = books.publishedBook(person, data);
        String slug = person.get("/api/v1/books/" + book + "/details").json().at("/slug").asString();

        assertThat(person.get("/api/v1/books/" + book + "/details").json().at("/coverUrl").asString()).isEqualTo(COVER);
        assertThat(visitor.get("/api/v1/books/" + slug).json().at("/coverUrl").asString()).isEqualTo(COVER);
        assertThat(visitor.get("/api/v1/books?author=" + username()).json().at("/content/0/coverUrl").asString()).isEqualTo(COVER);
        assertThat(person.get("/api/v1/users/me/books").json().at("/content/0/coverUrl").asString()).isEqualTo(COVER);
        assertThat(visitor.get("/api/v1/books/" + slug + "/chapters/capitulo-1").json().at("/book/coverUrl").asString()).isEqualTo(COVER);
    }

    @Test
    void aCoverCanBeChangedAndRemoved() {
        String book = books.createBook(person);
        String path = "/api/v1/books/" + book;

        assertThat(person.patch(path, body("coverUrl", COVER)).json().at("/coverUrl").asString()).isEqualTo(COVER);
        assertThat(person.patch(path, body("synopsis", "Outra")).json().at("/coverUrl").asString()).isEqualTo(COVER);
        assertThat(person.patch(path, body("coverUrl", " ")).json().at("/coverUrl").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://site-desconhecido.example.com/a.jpg", "https://i.imgur.com.golpe.example.com/a.jpg",
            "https://outro.i.imgur.com/a.jpg", "http://i.imgur.com/a.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA", "imgur.com/a.jpg",
            "https://user:senha@imgur.com/a.jpg", "https://127.0.0.1/a.jpg", "https://[::1]/a.jpg"})
    void aCoverOutsideHttpsOrPointingToAnAddressIsRefused(String url) {
        ApiClient.Response refused = person.patch("/api/v1/books/" + books.createBook(person), body("coverUrl", url));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("INVALID_COVER_URL");
    }

    @Test
    void theAllowedSitesArePublic() {
        assertThat(visitor.get("/api/v1/image-hosts").json().toString()).contains("i.imgur.com").contains("i.ibb.co")
                .contains("res.cloudinary.com").doesNotContain("example.com");
    }

    @Test
    void aBookCanBePaused() {
        String book = books.publishedBook(person);

        assertThat(person.patch("/api/v1/books/" + book, body("status", "PAUSED")).json().at("/status").asString()).isEqualTo("PAUSED");

        JsonNode paused = visitor.get("/api/v1/books?author=" + username() + "&status=PAUSED").json();
        assertThat(paused.at("/totalElements").asLong()).isOne();
        assertThat(paused.at("/content/0/status").asString()).isEqualTo("PAUSED");
        assertThat(visitor.get("/api/v1/books?author=" + username() + "&status=HIATUS").json().at("/totalElements").asLong()).isZero();
    }

    @Test
    void moderationRemovesOnlyTheCover() {
        Map<String, Object> data = new LinkedHashMap<>(books.newBook("Capa imprópria", BookFixtures.uniqueAcronym()));
        data.put("coverUrl", COVER);
        String book = books.publishedBook(person, data);
        String moderatorEmail = uniqueEmail();
        accounts.registerVerified(moderatorEmail);
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE email = ?", moderatorEmail);
        ApiClient moderator = accounts.loggedIn(moderatorEmail, PASSWORD);
        String remove = "/api/v1/moderation/books/" + book + "/remove-cover";

        assertThat(moderator.post(remove, body("reason", "Imagem imprópria")).status()).isEqualTo(204);

        JsonNode details = person.get("/api/v1/books/" + book + "/details").json();
        assertThat(details.at("/coverUrl").isNull()).isTrue();
        assertThat(details.at("/publicationState").asString()).isEqualTo("PUBLISHED");
        assertThat(moderator.post(remove, body("reason", "De novo")).code()).isEqualTo("NO_COVER");
    }

    @Test
    void theAdultGenreAlwaysMakesTheBookAdult() {
        UUID adult = books.genreId("Adulto");
        UUID drama = books.genreId("Drama");
        Map<String, Object> data = new LinkedHashMap<>(books.newBook("Obra adulta", BookFixtures.uniqueAcronym()));
        data.put("genreIds", List.of(adult));
        data.put("contentRating", "GENERAL");
        String created = person.post("/api/v1/books", data).json().at("/id").asString();
        String path = "/api/v1/books/" + created;

        assertThat(person.get(path + "/details").json().at("/contentRating").asString()).isEqualTo("ADULT");
        assertThat(person.patch(path, body("contentRating", "GENERAL")).json().at("/contentRating").asString()).isEqualTo("ADULT");

        String general = books.createBook(person);
        assertThat(person.patch("/api/v1/books/" + general, body("genreIds", List.of(adult))).json().at("/contentRating").asString()).isEqualTo("ADULT");

        assertThat(person.patch(path, body("genreIds", List.of(drama), "contentRating", "GENERAL")).json().at("/contentRating").asString()).isEqualTo("GENERAL");
    }
}
