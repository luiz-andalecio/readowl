package br.com.readowl.readowl.book;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.FakeRemoteImages;
import br.com.readowl.readowl.support.RecordingMailSender;
import br.com.readowl.readowl.support.RecordingObjectStorage;
import br.com.readowl.readowl.support.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.awt.image.BufferedImage;
import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class BookImagesIntegrationTest {

    private static final String LINK = "https://i.imgur.com/capa.png";
    private static final String CDN = "https://cdn.readowl.test/";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private RecordingObjectStorage storage;

    @Autowired
    private FakeRemoteImages remote;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private BookFixtures books;
    private String email;
    private ApiClient author;
    private ApiClient visitor;
    private String book;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
        storage.setFailing(false);
        remote.reset();
        email = uniqueEmail();
        accounts.registerVerified(email);
        author = accounts.loggedIn(email, PASSWORD);
        visitor = accounts.client();
        book = books.createBook(author);
    }

    private String path(String image) {
        return "/api/v1/books/" + book + "/" + image;
    }

    private ApiClient.Response uploadCover() {
        return author.putBytes(path("cover"), TestImages.png(600, 800), "image/png");
    }

    private String coverUrl() {
        ApiClient.Response uploaded = uploadCover();
        assertThat(uploaded.status()).isEqualTo(200);
        return uploaded.json().at("/coverUrl").asString();
    }

    private String bannerUrl() {
        ApiClient.Response uploaded = author.putBytes(path("banner"), TestImages.png(1200, 400), "image/png");
        assertThat(uploaded.status()).isEqualTo(200);
        return uploaded.json().at("/bannerUrl").asString();
    }

    private static String keyOf(String url) {
        return url.substring(CDN.length());
    }

    private String username() {
        return author.get("/api/v1/users/me").json().at("/username").asString();
    }

    private ApiClient moderator() {
        String moderatorEmail = uniqueEmail();
        accounts.registerVerified(moderatorEmail);
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE email = ?", moderatorEmail);
        return accounts.loggedIn(moderatorEmail, PASSWORD);
    }

    @Test
    void aCoverFileIsRewrittenStoredAndShownEverywhere() {
        String url = coverUrl();
        books.publishChapter(author, books.createChapter(author, book, "Capítulo 1"), "Era uma vez.");
        assertThat(author.post("/api/v1/books/" + book + "/publish", body()).status()).isEqualTo(200);
        String slug = author.get("/api/v1/books/" + book + "/details").json().at("/slug").asString();

        assertThat(url).startsWith(CDN + "covers/" + book + "/").endsWith(".jpg");
        assertThat(storage.get(keyOf(url))).startsWith((byte) 0xFF, (byte) 0xD8);
        assertThat(author.get("/api/v1/books/" + book + "/details").json().at("/coverUrl").asString()).isEqualTo(url);
        assertThat(visitor.get("/api/v1/books/" + slug).json().at("/coverUrl").asString()).isEqualTo(url);
        assertThat(visitor.get("/api/v1/books?author=" + username()).json().at("/content/0/coverUrl").asString()).isEqualTo(url);
        assertThat(author.get("/api/v1/users/me/books").json().at("/content/0/coverUrl").asString()).isEqualTo(url);
        assertThat(visitor.get("/api/v1/books/" + slug + "/chapters/capitulo-1").json().at("/book/coverUrl").asString()).isEqualTo(url);
    }

    @Test
    void aCoverLinkIsDownloadedAndACopyIsStored() {
        remote.serve(LINK, TestImages.png(600, 800));

        ApiClient.Response saved = author.put(path("cover"), body("url", " " + LINK + " "));

        assertThat(saved.status()).isEqualTo(200);
        String url = saved.json().at("/coverUrl").asString();
        assertThat(url).startsWith(CDN + "covers/");
        assertThat(storage.holds(keyOf(url))).isTrue();
        assertThat(remote.downloads()).containsExactly(LINK);
    }

    @Test
    void theBannerIsSeparateFromTheCoverAndShownOnTheBookPage() {
        String cover = coverUrl();
        remote.serve(LINK, TestImages.png(1200, 400));

        String banner = author.put(path("banner"), body("url", LINK)).json().at("/bannerUrl").asString();

        assertThat(banner).startsWith(CDN + "book-banners/" + book + "/");
        JsonNode details = author.get("/api/v1/books/" + book + "/details").json();
        assertThat(details.at("/coverUrl").asString()).isEqualTo(cover);
        assertThat(details.at("/bannerUrl").asString()).isEqualTo(banner);
        books.publishChapter(author, books.createChapter(author, book, "Capítulo 1"), "Era uma vez.");
        author.post("/api/v1/books/" + book + "/publish", body());
        assertThat(visitor.get("/api/v1/books/" + details.at("/slug").asString()).json().at("/bannerUrl").asString()).isEqualTo(banner);
    }

    @Test
    void largeImagesAreReducedToTheStoredSize() {
        String cover = author.putBytes(path("cover"), TestImages.png(1800, 2400), "image/png").json().at("/coverUrl").asString();
        String banner = author.putBytes(path("banner"), TestImages.png(3650, 1200), "image/png").json().at("/bannerUrl").asString();

        BufferedImage storedCover = TestImages.read(storage.get(keyOf(cover)));
        BufferedImage storedBanner = TestImages.read(storage.get(keyOf(banner)));
        assertThat(storedCover.getWidth()).isEqualTo(1200);
        assertThat(storedCover.getHeight()).isEqualTo(1600);
        assertThat(storedBanner.getWidth()).isEqualTo(2400);
        assertThat(storedBanner.getHeight()).isCloseTo(789, org.assertj.core.data.Offset.offset(1));
    }

    @Test
    void aSmallImageIsNotEnlarged() {
        String cover = author.putBytes(path("cover"), TestImages.png(600, 800), "image/png").json().at("/coverUrl").asString();

        BufferedImage stored = TestImages.read(storage.get(keyOf(cover)));
        assertThat(stored.getWidth()).isEqualTo(600);
        assertThat(stored.getHeight()).isEqualTo(800);
    }

    @ParameterizedTest
    @CsvSource({"cover,599,799,IMAGE_TOO_SMALL", "cover,600,799,IMAGE_TOO_SMALL", "cover,800,800,IMAGE_WRONG_PROPORTION", "cover,613,800,IMAGE_WRONG_PROPORTION",
            "cover,800,1100,IMAGE_WRONG_PROPORTION", "cover,6001,8000,IMAGE_TOO_LARGE",
            "banner,1199,400,IMAGE_TOO_SMALL", "banner,1200,399,IMAGE_TOO_SMALL", "banner,1200,800,IMAGE_WRONG_PROPORTION", "banner,1920,1080,IMAGE_WRONG_PROPORTION",
            "banner,6001,2000,IMAGE_TOO_LARGE"})
    void imagesOutsideTheRulesAreRefusedFromAFileOrALink(String image, int width, int height, String code) {
        byte[] file = TestImages.png(width, height);
        remote.serve(LINK, file);

        ApiClient.Response fromFile = author.putBytes(path(image), file, "image/png");
        ApiClient.Response fromLink = author.put(path(image), body("url", LINK));

        assertThat(fromFile.status()).isEqualTo(400);
        assertThat(fromFile.code()).isEqualTo(code);
        assertThat(fromLink.code()).isEqualTo(code);
        JsonNode details = author.get("/api/v1/books/" + book + "/details").json();
        assertThat(details.at("/coverUrl").isNull()).isTrue();
        assertThat(details.at("/bannerUrl").isNull()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"cover,612,800", "cover,800,1088", "banner,1224,400", "banner,1200,408"})
    void aSmallDifferenceInProportionIsTolerated(String image, int width, int height) {
        assertThat(author.putBytes(path(image), TestImages.png(width, height), "image/png").status()).isEqualTo(200);
    }

    @Test
    void theSizeRulesAreReportedForTheFrontend() {
        JsonNode refused = author.putBytes(path("banner"), TestImages.png(100, 100), "image/png").json();

        assertThat(refused.at("/minWidth").asInt()).isEqualTo(1200);
        assertThat(refused.at("/minHeight").asInt()).isEqualTo(400);
    }

    @Test
    void aFileNearTheLimitIsAccepted() {
        byte[] file = TestImages.noisyPng(1100, 1466);
        assertThat(file.length).isGreaterThan(4 * 1024 * 1024).isLessThan(5 * 1024 * 1024);

        assertThat(author.putBytes(path("cover"), file, "image/png").status()).isEqualTo(200);
    }

    @Test
    void aFileAboveTheLimitOrThatIsNotAnImageIsRefused() {
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];

        assertThat(author.putBytes(path("cover"), tooLarge, "image/png").code()).isEqualTo("IMAGE_FILE_TOO_LARGE");
        assertThat(author.putBytes(path("cover"), "<svg onload=alert(1)>".getBytes(), "image/png").code()).isEqualTo("INVALID_IMAGE");
        assertThat(author.putBytes(path("cover"), new byte[0], "image/png").code()).isEqualTo("INVALID_IMAGE");
        assertThat(author.putBytes(path("cover"), TestImages.png(600, 800), "image/svg+xml").status()).isEqualTo(415);
        remote.serve(LINK, "<html>".getBytes());
        assertThat(author.put(path("cover"), body("url", LINK)).code()).isEqualTo("INVALID_IMAGE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://site-desconhecido.example.com/a.jpg", "https://i.imgur.com.golpe.example.com/a.jpg",
            "https://outro.i.imgur.com/a.jpg", "http://i.imgur.com/a.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA", "imgur.com/a.jpg",
            "https://user:senha@imgur.com/a.jpg", "https://127.0.0.1/a.jpg", "https://[::1]/a.jpg", "file:///etc/passwd"})
    void aLinkOutsideTheAllowedSitesIsNeverDownloaded(String url) {
        assertThat(author.put(path("cover"), body("url", url)).code()).isEqualTo("INVALID_COVER_URL");
        assertThat(author.put(path("banner"), body("url", url)).code()).isEqualTo("INVALID_BANNER_URL");
        assertThat(remote.downloads()).isEmpty();
    }

    @Test
    void aFailedDownloadKeepsTheCurrentImage() {
        String current = coverUrl();

        ApiClient.Response failed = author.put(path("cover"), body("url", "https://i.imgur.com/sumiu.png"));

        assertThat(failed.status()).isEqualTo(400);
        assertThat(failed.code()).isEqualTo("IMAGE_DOWNLOAD_FAILED");
        assertThat(author.get("/api/v1/books/" + book + "/details").json().at("/coverUrl").asString()).isEqualTo(current);
        assertThat(storage.holds(keyOf(current))).isTrue();
    }

    @Test
    void aStorageFailureAnswers503AndKeepsTheCurrentImage() {
        String current = coverUrl();
        storage.setFailing(true);

        ApiClient.Response failed = uploadCover();

        assertThat(failed.status()).isEqualTo(503);
        assertThat(failed.code()).isEqualTo("IMAGES_UNAVAILABLE");
        assertThat(author.get("/api/v1/books/" + book + "/details").json().at("/coverUrl").asString()).isEqualTo(current);
        assertThat(storage.holds(keyOf(current))).isTrue();
    }

    @Test
    void someoneElseCannotChangeTheImagesOrMakeTheServerDownloadAnything() {
        String current = coverUrl();
        String otherEmail = uniqueEmail();
        accounts.registerVerified(otherEmail);
        ApiClient other = accounts.loggedIn(otherEmail, PASSWORD);
        remote.serve(LINK, TestImages.png(600, 800));

        for (String image : new String[]{"cover", "banner"}) {
            assertThat(other.putBytes(path(image), TestImages.png(1200, 400), "image/png").status()).isEqualTo(404);
            assertThat(other.put(path(image), body("url", LINK)).status()).isEqualTo(404);
            assertThat(other.delete(path(image)).status()).isEqualTo(404);
            assertThat(visitor.put(path(image), body("url", LINK)).status()).isEqualTo(401);
        }

        assertThat(remote.downloads()).isEmpty();
        assertThat(author.get("/api/v1/books/" + book + "/details").json().at("/coverUrl").asString()).isEqualTo(current);
        assertThat(storage.holds(keyOf(current))).isTrue();
    }

    @Test
    void anUnknownBookDoesNotReachTheDownload() {
        remote.serve(LINK, TestImages.png(600, 800));

        assertThat(author.put("/api/v1/books/" + UUID.randomUUID() + "/cover", body("url", LINK)).status()).isEqualTo(404);
        assertThat(remote.downloads()).isEmpty();
    }

    @Test
    void replacingAnImageDeletesThePreviousFile() {
        String first = coverUrl();
        remote.serve(LINK, TestImages.png(600, 800));

        String second = author.put(path("cover"), body("url", LINK)).json().at("/coverUrl").asString();
        assertThat(storage.holds(keyOf(first))).isFalse();
        assertThat(storage.holds(keyOf(second))).isTrue();

        String third = coverUrl();
        assertThat(storage.holds(keyOf(second))).isFalse();
        assertThat(storage.holds(keyOf(third))).isTrue();
    }

    @Test
    void removingAnImageDeletesTheFileAndCanBeRepeated() {
        String cover = coverUrl();
        String banner = bannerUrl();

        assertThat(author.delete(path("cover")).status()).isEqualTo(204);
        assertThat(author.delete(path("cover")).status()).isEqualTo(204);

        JsonNode details = author.get("/api/v1/books/" + book + "/details").json();
        assertThat(details.at("/coverUrl").isNull()).isTrue();
        assertThat(details.at("/bannerUrl").asString()).isEqualTo(banner);
        assertThat(storage.holds(keyOf(cover))).isFalse();
        assertThat(storage.holds(keyOf(banner))).isTrue();
    }

    @Test
    void aCoverSavedAsALinkBeforeThisChangeKeepsWorkingUntilReplaced() {
        String old = "https://i.imgur.com/antiga.jpg";
        jdbc.update("UPDATE books SET cover_url = ? WHERE id = ?::uuid", old, book);

        assertThat(author.get("/api/v1/books/" + book + "/details").json().at("/coverUrl").asString()).isEqualTo(old);
        assertThat(remote.downloads()).isEmpty();

        String url = coverUrl();
        assertThat(url).startsWith(CDN);
        assertThat(author.delete(path("cover")).status()).isEqualTo(204);
        assertThat(storage.holds(keyOf(url))).isFalse();
    }

    @Test
    void theRemovedFieldsOfTheBookFormAreIgnored() {
        assertThat(author.patch("/api/v1/books/" + book, body("coverUrl", LINK)).json().at("/coverUrl").isNull()).isTrue();
        assertThat(remote.downloads()).isEmpty();
    }

    @Test
    void anotherEditKeepsTheImages() {
        String cover = coverUrl();

        assertThat(author.patch("/api/v1/books/" + book, body("synopsis", "Outra")).json().at("/coverUrl").asString()).isEqualTo(cover);
    }

    @Test
    void deletingTheBookDeletesItsFiles() {
        String cover = coverUrl();
        String banner = bannerUrl();

        assertThat(author.delete("/api/v1/books/" + book, body("confirmTitle", "Obra de Teste")).status()).isEqualTo(204);

        assertThat(storage.holds(keyOf(cover))).isFalse();
        assertThat(storage.holds(keyOf(banner))).isFalse();
    }

    @Test
    void deletingTheAccountDeletesTheFilesOfItsBooks() {
        String cover = coverUrl();
        String banner = bannerUrl();

        assertThat(author.delete("/api/v1/users/me", body("password", PASSWORD)).status()).isEqualTo(204);

        assertThat(storage.holds(keyOf(cover))).isFalse();
        assertThat(storage.holds(keyOf(banner))).isFalse();
    }

    @Test
    void aBookKeptForAnOpenReportKeepsItsFilesWhenTheAccountIsDeleted() {
        String cover = coverUrl();
        books.publishChapter(author, books.createChapter(author, book, "Capítulo 1"), "Era uma vez.");
        author.post("/api/v1/books/" + book + "/publish", body());
        String reporterEmail = uniqueEmail();
        accounts.registerVerified(reporterEmail);
        assertThat(accounts.loggedIn(reporterEmail, PASSWORD).post("/api/v1/reports",
                body("targetType", "BOOK", "targetId", book, "reason", "ABUSE")).status()).isEqualTo(201);

        assertThat(author.delete("/api/v1/users/me", body("password", PASSWORD)).status()).isEqualTo(204);

        assertThat(storage.holds(keyOf(cover))).isTrue();
    }

    @Test
    void moderationRemovesOnlyTheImageAndDeletesTheFile() {
        String cover = coverUrl();
        String banner = bannerUrl();
        books.publishChapter(author, books.createChapter(author, book, "Capítulo 1"), "Era uma vez.");
        author.post("/api/v1/books/" + book + "/publish", body());
        ApiClient moderator = moderator();

        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/remove-cover", body("reason", "Imagem imprópria")).status()).isEqualTo(204);
        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/remove-banner", body("reason", "Imagem imprópria")).status()).isEqualTo(204);

        JsonNode details = author.get("/api/v1/books/" + book + "/details").json();
        assertThat(details.at("/coverUrl").isNull()).isTrue();
        assertThat(details.at("/bannerUrl").isNull()).isTrue();
        assertThat(details.at("/publicationState").asString()).isEqualTo("PUBLISHED");
        assertThat(storage.holds(keyOf(cover))).isFalse();
        assertThat(storage.holds(keyOf(banner))).isFalse();
        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/remove-cover", body("reason", "De novo")).code()).isEqualTo("NO_COVER");
        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/remove-banner", body("reason", "De novo")).code()).isEqualTo("NO_BOOK_BANNER");
    }

    @Test
    void anOrdinaryUserCannotUseTheModerationRoutes() {
        assertThat(author.post("/api/v1/moderation/books/" + book + "/remove-banner", body("reason", "Quero")).status()).isEqualTo(403);
    }

    @Test
    void thereIsALimitPerHourSharedByAllImages() {
        for (int i = 0; i < 20; i++) {
            assertThat(author.put(path(i % 2 == 0 ? "cover" : "banner"), body("url", "https://fora.example.com/a.png")).status()).isEqualTo(400);
        }

        assertThat(author.put(path("cover"), body("url", LINK)).status()).isEqualTo(429);
        assertThat(remote.downloads()).isEmpty();
    }

    @Test
    void theExportIncludesTheImagesOfTheBooks() {
        String cover = coverUrl();
        String banner = bannerUrl();

        assertThat(author.get("/api/v1/users/me/export").body()).contains(cover).contains(banner);
    }
}
