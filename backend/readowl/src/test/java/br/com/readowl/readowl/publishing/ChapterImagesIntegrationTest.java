package br.com.readowl.readowl.publishing;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.FakeRemoteImages;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class ChapterImagesIntegrationTest {

    private static final String IMAGE = "https://i.imgur.com/ilustracao.jpg";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private FakeRemoteImages remote;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private ApiClient author;
    private String book;
    private String chapter;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        mail.setFailing(false);
        remote.reset();
        BookFixtures books = new BookFixtures(jdbc);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        author = accounts.loggedIn(email, PASSWORD);
        book = books.createBook(author);
        chapter = books.createChapter(author, book, "Capítulo 1");
    }

    private static Map<String, Object> imageBlock(String src) {
        return body("type", "image", "attrs", body("src", src, "alt", "Ilustração"));
    }

    private ApiClient.Response save(List<Map<String, Object>> content) {
        String path = "/api/v1/chapters/" + chapter + "/draft";
        long version = author.get(path).json().at("/version").asLong();
        return author.put(path, body("version", version, "document", body("type", "doc", "content", content)));
    }

    private String publishAndRead(ApiClient.Response saved) {
        assertThat(saved.status()).isEqualTo(200);
        assertThat(author.post("/api/v1/chapters/" + chapter + "/publish", body("draftVersion", saved.json().at("/version").asLong())).status()).isEqualTo(200);
        assertThat(author.post("/api/v1/books/" + book + "/publish", body()).status()).isEqualTo(200);
        String slug = author.get("/api/v1/books/" + book + "/details").json().at("/slug").asString();
        return accounts.client().get("/api/v1/books/" + slug + "/chapters/capitulo-1").json().at("/html").asString();
    }

    @Test
    void aChapterShowsAnImageFromAnAllowedSiteWithoutDownloadingIt() {
        String html = publishAndRead(save(List.of(BookFixtures.document("Era uma vez.").entrySet().stream()
                .filter(entry -> entry.getKey().equals("content")).map(entry -> ((List<Map<String, Object>>) entry.getValue()).getFirst()).findFirst().orElseThrow(),
                imageBlock(IMAGE))));

        assertThat(html).isEqualTo("<p>Era uma vez.</p><img src=\"" + IMAGE + "\" alt=\"Ilustração\" loading=\"lazy\" referrerpolicy=\"no-referrer\">");
        assertThat(remote.downloads()).isEmpty();
    }

    @Test
    void aChapterWithOnlyImagesCanBePublished() {
        assertThat(publishAndRead(save(List.of(imageBlock(IMAGE))))).contains("<img src=\"" + IMAGE + "\"");
    }

    @Test
    void anImageOutsideTheAllowedSitesIsRefusedOnSave() {
        ApiClient.Response refused = save(List.of(imageBlock("https://site-desconhecido.example.com/a.jpg")));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("INVALID_DOCUMENT");
    }
}
