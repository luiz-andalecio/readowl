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

import java.util.ArrayList;
import java.util.List;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class ReadingIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private BookFixtures books;
    private ApiClient anonymous;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        anonymous = accounts.client();
        mail.setFailing(false);
    }

    private ApiClient newAuthor() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private static String slugOf(ApiClient author, String bookId) {
        return author.get("/api/v1/books/" + bookId + "/details").json().at("/slug").asString();
    }

    private static String bookPath(String slug) {
        return "/api/v1/books/" + slug;
    }

    private static String chapterPath(String slug, String chapterSlug) {
        return "/api/v1/books/" + slug + "/chapters/" + chapterSlug;
    }

    @Test
    void visitorsReadThePublishedBookWithoutSessionOrCookies() {
        ApiClient author = newAuthor();
        String slug = slugOf(author, books.publishedBook(author));

        ApiClient.Response book = anonymous.get(bookPath(slug));
        ApiClient.Response chapter = anonymous.get(chapterPath(slug, "capitulo-1"));

        assertThat(book.status()).isEqualTo(200);
        assertThat(book.json().at("/slug").asString()).isEqualTo(slug);
        assertThat(book.json().at("/author/name").asString()).isEqualTo("Pessoa Leitora");
        assertThat(book.json().at("/viewCount").asLong()).isZero();
        assertThat(book.json().at("/chapters/0/slug").asString()).isEqualTo("capitulo-1");
        assertThat(book.setCookies()).isEmpty();

        assertThat(chapter.status()).isEqualTo(200);
        assertThat(chapter.json().at("/html").asString()).isEqualTo("<p>Era uma vez.</p>");
        assertThat(chapter.json().at("/book/slug").asString()).isEqualTo(slug);
        assertThat(chapter.json().at("/publishedAt").asString()).endsWith("Z");
        assertThat(chapter.setCookies()).isEmpty();
    }

    @Test
    void readerSeesThePublishedRevisionNotTheDraft() {
        ApiClient author = newAuthor();
        String id = books.publishedBook(author);
        String slug = slugOf(author, id);
        String chapter = author.get("/api/v1/books/" + id + "/contents").json().at("/chapters/0/id").asString();

        books.writeDraft(author, chapter, "Rascunho secreto em edição");

        ApiClient.Response reading = anonymous.get(chapterPath(slug, "capitulo-1"));
        assertThat(reading.body()).contains("Era uma vez.").doesNotContain("Rascunho secreto");
    }

    @Test
    void responsesExposeNoPrivateFields() {
        ApiClient author = newAuthor();
        String id = books.publishedBook(author);
        String slug = slugOf(author, id);
        String email = jdbc.queryForObject("""
                SELECT u.email FROM books b JOIN users u ON u.id = b.author_id WHERE b.slug = ?
                """, String.class, slug);

        ApiClient.Response book = anonymous.get(bookPath(slug));
        ApiClient.Response chapter = anonymous.get(chapterPath(slug, "capitulo-1"));

        assertThat(book.body()).doesNotContain(email).doesNotContain("publicationState");
        assertThat(chapter.body()).doesNotContain(email).doesNotContain("plainText").doesNotContain("document")
                .doesNotContain("viewCount").doesNotContain("revisionNumber");
        assertThat(book.json().at("/chapters/0").has("viewCount")).isFalse();
    }

    @Test
    void privateContentLooksLikeItDoesNotExistEvenForItsAuthorAndAdmins() {
        ApiClient author = newAuthor();
        String id = books.createBook(author);
        String slug = slugOf(author, id);
        books.publishChapter(author, books.createChapter(author, id, "Um"), "Texto");
        String adminEmail = uniqueEmail();
        accounts.registerVerified(adminEmail);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", adminEmail);

        for (ApiClient reader : List.of(anonymous, author, accounts.loggedIn(adminEmail, PASSWORD))) {
            assertThat(reader.get(bookPath(slug)).code()).isEqualTo("BOOK_NOT_FOUND");
            assertThat(reader.get(chapterPath(slug, "um")).code()).isEqualTo("BOOK_NOT_FOUND");
        }
    }

    @Test
    void draftAndUnpublishedChaptersOfAPublishedBookAreHidden() {
        ApiClient author = newAuthor();
        String id = books.publishedBook(author);
        String slug = slugOf(author, id);
        books.createChapter(author, id, "Rascunho Oculto");
        String removed = books.createChapter(author, id, "Retirado");
        books.publishChapter(author, removed, "Texto retirado");
        author.post("/api/v1/chapters/" + removed + "/unpublish", body());

        ApiClient.Response book = anonymous.get(bookPath(slug));

        assertThat(book.json().at("/chapters").size()).isEqualTo(1);
        assertThat(book.body()).doesNotContain("Rascunho Oculto").doesNotContain("Retirado");
        assertThat(anonymous.get(chapterPath(slug, "rascunho-oculto")).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(anonymous.get(chapterPath(slug, "retirado")).code()).isEqualTo("CHAPTER_NOT_FOUND");
    }

    @Test
    void volumesWithoutPublishedChaptersAreHidden() {
        ApiClient author = newAuthor();
        String id = books.publishedBook(author);
        String secret = author.post("/api/v1/books/" + id + "/volumes", body("title", "Volume Secreto")).json().at("/id").asString();
        author.post("/api/v1/books/" + id + "/chapters", body("title", "Oculto", "volumeId", secret));

        ApiClient.Response book = anonymous.get(bookPath(slugOf(author, id)));

        assertThat(book.json().at("/volumes").size()).isZero();
        assertThat(book.body()).doesNotContain("Volume Secreto");
    }

    @Test
    void unpublishingTheBookHidesEverythingAtOnce() {
        ApiClient author = newAuthor();
        String id = books.publishedBook(author);
        String slug = slugOf(author, id);
        assertThat(anonymous.get(chapterPath(slug, "capitulo-1")).status()).isEqualTo(200);

        author.post("/api/v1/books/" + id + "/unpublish", body());

        assertThat(anonymous.get(bookPath(slug)).status()).isEqualTo(404);
        assertThat(anonymous.get(chapterPath(slug, "capitulo-1")).status()).isEqualTo(404);
    }

    @Test
    void suspendedAuthorsContentIsHidden() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient author = accounts.loggedIn(email, PASSWORD);
        String slug = slugOf(author, books.publishedBook(author));

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        assertThat(anonymous.get(bookPath(slug)).status()).isEqualTo(404);
        assertThat(anonymous.get(chapterPath(slug, "capitulo-1")).status()).isEqualTo(404);
    }

    @Test
    void chapterOfAnotherBookIsNotReachableThroughThisBook() {
        ApiClient author = newAuthor();
        String slug = slugOf(author, books.publishedBook(author));
        String other = books.createBook(author);
        books.publishChapter(author, books.createChapter(author, other, "De outra obra"), "Texto");

        assertThat(anonymous.get(chapterPath(slug, "de-outra-obra")).code()).isEqualTo("CHAPTER_NOT_FOUND");
    }

    @Test
    void readingOrderAndNavigationCrossVolumesAndSkipUnpublished() {
        ApiClient author = newAuthor();
        String id = books.createBook(author);
        String secondVolume = author.post("/api/v1/books/" + id + "/volumes", body("title", "Volume B")).json().at("/id").asString();
        String firstVolume = author.post("/api/v1/books/" + id + "/volumes", body("title", "Volume A")).json().at("/id").asString();
        author.put("/api/v1/books/" + id + "/volumes/order", body("volumeIds", List.of(firstVolume, secondVolume)));

        String inB = author.post("/api/v1/books/" + id + "/chapters", body("title", "Em B", "volumeId", secondVolume)).json().at("/id").asString();
        String inA = author.post("/api/v1/books/" + id + "/chapters", body("title", "Em A", "volumeId", firstVolume)).json().at("/id").asString();
        String hiddenInA = author.post("/api/v1/books/" + id + "/chapters", body("title", "Oculto", "volumeId", firstVolume)).json().at("/id").asString();
        String loose = books.createChapter(author, id, "Prólogo");
        for (String chapter : List.of(inB, inA, loose)) {
            books.publishChapter(author, chapter, "Texto");
        }
        assertThat(hiddenInA).isNotBlank();
        author.post("/api/v1/books/" + id + "/publish", body());
        String slug = slugOf(author, id);

        JsonNode book = anonymous.get(bookPath(slug)).json();
        List<String> order = new ArrayList<>();
        book.at("/chapters").values().forEach(chapter -> order.add(chapter.at("/slug").asString()));
        assertThat(order).containsExactly("prologo", "em-a", "em-b");
        assertThat(book.at("/volumes/0/title").asString()).isEqualTo("Volume A");
        assertThat(book.at("/volumes/1/title").asString()).isEqualTo("Volume B");

        JsonNode first = anonymous.get(chapterPath(slug, "prologo")).json();
        assertThat(first.at("/previous").isNull()).isTrue();
        assertThat(first.at("/next/slug").asString()).isEqualTo("em-a");
        assertThat(first.at("/volume").isNull()).isTrue();

        JsonNode middle = anonymous.get(chapterPath(slug, "em-a")).json();
        assertThat(middle.at("/previous/slug").asString()).isEqualTo("prologo");
        assertThat(middle.at("/next/slug").asString()).isEqualTo("em-b");
        assertThat(middle.at("/volume/title").asString()).isEqualTo("Volume A");

        JsonNode last = anonymous.get(chapterPath(slug, "em-b")).json();
        assertThat(last.at("/next").isNull()).isTrue();
    }

    @Test
    void renamingChangesTheAddressAndTheOldOneStopsWorking() {
        ApiClient author = newAuthor();
        String id = books.publishedBook(author);
        String oldSlug = slugOf(author, id);
        String chapter = author.get("/api/v1/books/" + id + "/contents").json().at("/chapters/0/id").asString();

        String newSlug = author.patch("/api/v1/books/" + id, body("title", "Título Totalmente Novo")).json().at("/slug").asString();
        author.patch("/api/v1/chapters/" + chapter, body("title", "Capítulo Novo"));

        assertThat(newSlug).isNotEqualTo(oldSlug);
        assertThat(anonymous.get(bookPath(oldSlug)).status()).isEqualTo(404);
        assertThat(anonymous.get(bookPath(newSlug)).status()).isEqualTo(200);
        assertThat(anonymous.get(chapterPath(newSlug, "capitulo-1")).status()).isEqualTo(404);
        assertThat(anonymous.get(chapterPath(newSlug, "capitulo-novo")).status()).isEqualTo(200);
    }

    @Test
    void managementRoutesStayPrivateNextToThePublicOnes() {
        ApiClient author = newAuthor();
        String id = books.publishedBook(author);

        assertThat(anonymous.get("/api/v1/books/" + id + "/details").status()).isEqualTo(401);
        assertThat(anonymous.get("/api/v1/books/" + id + "/contents").status()).isEqualTo(401);
        assertThat(anonymous.post("/api/v1/books/" + id + "/chapters", body("title", "X")).status()).isEqualTo(401);
    }
}
