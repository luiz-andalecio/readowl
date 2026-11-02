package br.com.readowl.readowl.publishing;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * O que traz leitores de fora: endereços que continuam valendo depois de renomear, sitemap e nota no catálogo.
 */
@ApiIntegrationTest
class AudienceReachIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private BookFixtures books;
    private ApiClient author;
    private ApiClient visitor;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
        author = person();
        visitor = accounts.client();
    }

    private ApiClient person() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private JsonNode details(String book) {
        return author.get("/api/v1/books/" + book + "/details").json();
    }

    private String rename(String book, String title) {
        assertThat(author.patch("/api/v1/books/" + book, body("title", title)).status()).isEqualTo(200);
        return details(book).at("/slug").asString();
    }

    private static String unique() {
        return AccountFixtures.unique();
    }

    @Test
    void anOldBookAddressPointsToTheNewOne() {
        String book = books.publishedBook(author);
        String oldSlug = details(book).at("/slug").asString();

        String newSlug = rename(book, "Título novo " + unique());

        assertThat(newSlug).isNotEqualTo(oldSlug);
        ApiClient.Response moved = visitor.get("/api/v1/books/" + oldSlug);
        assertThat(moved.status()).isEqualTo(404);
        assertThat(moved.code()).isEqualTo("BOOK_MOVED");
        assertThat(moved.json().at("/currentSlug").asString()).isEqualTo(newSlug);
        assertThat(visitor.get("/api/v1/books/" + newSlug).status()).isEqualTo(200);
    }

    @Test
    void severalRenamesAllPointToTheCurrentAddressAndGoingBackWorks() {
        String book = books.publishedBook(author);
        String first = details(book).at("/slug").asString();
        String firstTitle = details(book).at("/title").asString();
        String second = rename(book, "Segundo " + unique());
        String third = rename(book, "Terceiro " + unique());

        assertThat(visitor.get("/api/v1/books/" + first).json().at("/currentSlug").asString()).isEqualTo(third);
        assertThat(visitor.get("/api/v1/books/" + second).json().at("/currentSlug").asString()).isEqualTo(third);

        rename(book, firstTitle);

        assertThat(visitor.get("/api/v1/books/" + first).status()).isEqualTo(200);
        assertThat(visitor.get("/api/v1/books/" + third).json().at("/currentSlug").asString()).isEqualTo(first);
    }

    @Test
    void aBookThatTakesAnOldAddressKeepsIt() {
        String book = books.publishedBook(author);
        JsonNode original = details(book);
        rename(book, "Outro nome " + unique());

        var again = new java.util.LinkedHashMap<>(books.newBook(original.at("/title").asString(), original.at("/acronym").asString()));
        String taken = books.publishedBook(person(), again);

        assertThat(visitor.get("/api/v1/books/" + original.at("/slug").asString()).json().at("/id").asString()).isEqualTo(taken);
    }

    @Test
    void anOldAddressRevealsNothingWhileTheBookIsOffTheAir() {
        String book = books.publishedBook(author);
        String oldSlug = details(book).at("/slug").asString();
        rename(book, "Nome novo " + unique());
        assertThat(author.post("/api/v1/books/" + book + "/unpublish", body()).status()).isEqualTo(200);

        ApiClient.Response response = visitor.get("/api/v1/books/" + oldSlug);

        assertThat(response.code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(response.body()).doesNotContain("currentSlug");
    }

    @Test
    void anOldAddressOfAnAdultBookIsNotShownToVisitors() {
        String book = books.publishedBook(author);
        String oldSlug = details(book).at("/slug").asString();
        rename(book, "Nome adulto " + unique());
        assertThat(author.patch("/api/v1/books/" + book, body("contentRating", "ADULT")).status()).isEqualTo(200);

        assertThat(visitor.get("/api/v1/books/" + oldSlug).code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(person().get("/api/v1/books/" + oldSlug).code()).isEqualTo("BOOK_MOVED");
    }

    @Test
    void anOldChapterAddressPointsToTheNewOne() {
        String book = books.createBook(author);
        String chapter = books.createChapter(author, book, "Primeiro nome");
        books.publishChapter(author, chapter, "Texto");
        assertThat(author.post("/api/v1/books/" + book + "/publish", body()).status()).isEqualTo(200);
        String slug = details(book).at("/slug").asString();
        String path = "/api/v1/books/" + slug + "/chapters/";
        assertThat(visitor.get(path + "primeiro-nome").status()).isEqualTo(200);

        assertThat(author.patch("/api/v1/chapters/" + chapter, body("title", "Segundo nome")).status()).isEqualTo(200);

        ApiClient.Response moved = visitor.get(path + "primeiro-nome");
        assertThat(moved.code()).isEqualTo("CHAPTER_MOVED");
        assertThat(moved.json().at("/currentSlug").asString()).isEqualTo("segundo-nome");
        assertThat(visitor.get(path + "segundo-nome").status()).isEqualTo(200);
        assertThat(visitor.get(path + "nunca-existiu").code()).isEqualTo("CHAPTER_NOT_FOUND");
    }

    @Test
    void anOldChapterAddressRevealsNothingWhileItIsUnpublished() {
        String book = books.createBook(author);
        String chapter = books.createChapter(author, book, "Sairá do ar");
        String other = books.createChapter(author, book, "Fica no ar");
        books.publishChapter(author, chapter, "Texto");
        books.publishChapter(author, other, "Texto");
        assertThat(author.post("/api/v1/books/" + book + "/publish", body()).status()).isEqualTo(200);
        String path = "/api/v1/books/" + details(book).at("/slug").asString() + "/chapters/";
        assertThat(author.patch("/api/v1/chapters/" + chapter, body("title", "Nome novo")).status()).isEqualTo(200);
        assertThat(author.post("/api/v1/chapters/" + chapter + "/unpublish", body()).status()).isEqualTo(200);

        assertThat(visitor.get(path + "saira-do-ar").code()).isEqualTo("CHAPTER_NOT_FOUND");
    }

    private String allSitemapFiles() {
        Matcher file = Pattern.compile("<loc>[^<]*/sitemap/(\\d+)\\.xml</loc>").matcher(visitor.get("/sitemap.xml").body());
        StringBuilder all = new StringBuilder();
        while (file.find()) {
            all.append(visitor.get("/sitemap/" + file.group(1) + ".xml").body());
        }
        return all.toString();
    }

    @Test
    void theSitemapListsWhatAVisitorCanOpenAndNothingElse() {
        String published = books.publishedBook(author);
        String publishedSlug = details(published).at("/slug").asString();
        String draft = books.createBook(author);
        String draftSlug = details(draft).at("/slug").asString();
        String adult = books.publishedBook(author);
        String adultSlug = details(adult).at("/slug").asString();
        assertThat(author.patch("/api/v1/books/" + adult, body("contentRating", "ADULT")).status()).isEqualTo(200);

        ApiClient.Response index = visitor.get("/sitemap.xml");
        assertThat(index.status()).isEqualTo(200);
        assertThat(index.headers().firstValue("Content-Type").orElseThrow()).contains("xml");
        assertThat(index.headers().firstValue("Cache-Control").orElseThrow()).contains("public").contains("max-age=3600");
        assertThat(index.body()).contains("<sitemapindex").contains("https://readowl.test/sitemap/1.xml");

        String urls = allSitemapFiles();
        assertThat(urls).contains("<loc>https://readowl.test/obras/" + publishedSlug + "</loc>")
                .contains("<loc>https://readowl.test/obras/" + publishedSlug + "/capitulos/capitulo-1</loc>")
                .contains("<lastmod>")
                .doesNotContain("/obras/" + draftSlug + "<").doesNotContain("/obras/" + adultSlug);
    }

    @Test
    void aMissingSitemapFileAnswers404() {
        assertThat(visitor.get("/sitemap/999999.xml").status()).isEqualTo(404);
        assertThat(visitor.get("/sitemap/0.xml").status()).isEqualTo(404);
    }

    @Test
    void theCatalogShowsTheAverageRating() {
        String username = uniqueUsername();
        String book = books.publishedBook(author);
        String catalog = "/api/v1/books?author=" + username;

        JsonNode before = visitor.get(catalog).json().at("/content/0");
        assertThat(before.at("/averageRating").isNull()).isTrue();
        assertThat(before.at("/ratingCount").asLong()).isZero();

        assertThat(person().put("/api/v1/books/" + book + "/rating/me", body("score", 5)).status()).isIn(200, 204);
        assertThat(person().put("/api/v1/books/" + book + "/rating/me", body("score", 2)).status()).isIn(200, 204);

        JsonNode after = visitor.get(catalog).json().at("/content/0");
        assertThat(after.at("/averageRating").asDouble()).isEqualTo(3.5);
        assertThat(after.at("/ratingCount").asLong()).isEqualTo(2);
    }

    private String uniqueUsername() {
        return author.get("/api/v1/users/me").json().at("/username").asString();
    }
}
