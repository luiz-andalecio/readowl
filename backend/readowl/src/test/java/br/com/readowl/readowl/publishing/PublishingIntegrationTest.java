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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static br.com.readowl.readowl.support.BookFixtures.document;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class PublishingIntegrationTest {

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

    private ApiClient newAuthor() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private ApiClient withRole(String role) {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = ? WHERE email = ?", role, email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private static String draftPath(String chapterId) {
        return "/api/v1/chapters/" + chapterId + "/draft";
    }

    private static String publishPath(String chapterId) {
        return "/api/v1/chapters/" + chapterId + "/publish";
    }

    private String publishedHtml(String chapterId) {
        return jdbc.queryForObject("""
                SELECT r.html FROM chapters c JOIN chapter_revisions r ON r.id = c.published_revision_id WHERE c.id = ?
                """, String.class, UUID.fromString(chapterId));
    }

    private int revisionCount(String chapterId) {
        return jdbc.queryForObject("SELECT count(*) FROM chapter_revisions WHERE chapter_id = ?", Integer.class,
                UUID.fromString(chapterId));
    }

    @Test
    void chapterSlugFollowsTheTitleAndRepeatsGetANumber() {
        ApiClient author = newAuthor();
        String book = books.createBook(author);

        ApiClient.Response first = author.post("/api/v1/books/" + book + "/chapters", body("title", "Prólogo: Início"));
        ApiClient.Response second = author.post("/api/v1/books/" + book + "/chapters", body("title", "Prólogo: Início"));

        assertThat(first.json().at("/slug").asString()).isEqualTo("prologo-inicio");
        assertThat(first.json().at("/state").asString()).isEqualTo("DRAFT");
        assertThat(second.json().at("/slug").asString()).isEqualTo("prologo-inicio-2");
        assertThat(second.json().at("/displayOrder").asInt()).isEqualTo(2);
    }

    @Test
    void renamingAChapterChangesItsSlug() {
        ApiClient author = newAuthor();
        String chapter = books.createChapter(author, books.createBook(author), "Parte Um");
        String path = "/api/v1/chapters/" + chapter;

        assertThat(author.patch(path, body("title", "Parte")).json().at("/slug").asString()).isEqualTo("parte");
        assertThat(author.patch(path, body("title", "Outro Nome")).json().at("/slug").asString()).isEqualTo("outro-nome");
    }

    @Test
    void renamingAChapterToATakenTitleGetsANumberAndKeepingItsOwnSlugDoesNot() {
        ApiClient author = newAuthor();
        String book = books.createBook(author);
        books.createChapter(author, book, "Ocupado");
        String chapter = books.createChapter(author, book, "Livre");

        assertThat(author.patch("/api/v1/chapters/" + chapter, body("title", "Ocupado")).json().at("/slug").asString())
                .isEqualTo("ocupado-2");
        assertThat(author.patch("/api/v1/chapters/" + chapter, body("title", "OCUPADO")).json().at("/slug").asString())
                .isEqualTo("ocupado-2");
    }

    @Test
    void draftSaveDoesNotChangeThePublishedContent() {
        ApiClient author = newAuthor();
        String chapter = books.createChapter(author, books.createBook(author), "Capítulo");
        books.publishChapter(author, chapter, "Texto publicado");

        books.writeDraft(author, chapter, "Texto ainda em edição");

        assertThat(publishedHtml(chapter)).isEqualTo("<p>Texto publicado</p>");
        assertThat(author.get(draftPath(chapter)).json().at("/document/content/0/content/0/text").asString())
                .isEqualTo("Texto ainda em edição");
        assertThat(revisionCount(chapter)).isEqualTo(1);
    }

    @Test
    void staleDraftVersionFromAnotherTabGetsConflictAndDoesNotOverwrite() {
        ApiClient author = newAuthor();
        String chapter = books.createChapter(author, books.createBook(author), "Capítulo");
        long version = author.get(draftPath(chapter)).json().at("/version").asLong();

        ApiClient.Response firstTab = author.put(draftPath(chapter), body("version", version, "document", document("Primeira aba")));
        ApiClient.Response secondTab = author.put(draftPath(chapter), body("version", version, "document", document("Segunda aba")));

        assertThat(firstTab.status()).isEqualTo(200);
        assertThat(secondTab.status()).isEqualTo(409);
        assertThat(secondTab.code()).isEqualTo("DRAFT_CONFLICT");
        assertThat(secondTab.json().at("/currentVersion").asLong()).isEqualTo(firstTab.json().at("/version").asLong());
        assertThat(author.get(draftPath(chapter)).json().at("/document/content/0/content/0/text").asString())
                .isEqualTo("Primeira aba");
    }

    @Test
    void simultaneousDraftSavesDoNotLoseWorkSilently() {
        ApiClient author = newAuthor();
        String chapter = books.createChapter(author, books.createBook(author), "Capítulo");
        long version = author.get(draftPath(chapter)).json().at("/version").asLong();

        List<CompletableFuture<ApiClient.Response>> saves = List.of(
                CompletableFuture.supplyAsync(() -> author.put(draftPath(chapter), body("version", version, "document", document("A")))),
                CompletableFuture.supplyAsync(() -> author.put(draftPath(chapter), body("version", version, "document", document("B")))),
                CompletableFuture.supplyAsync(() -> author.put(draftPath(chapter), body("version", version, "document", document("C")))));

        List<Integer> statuses = saves.stream().map(save -> save.join().status()).toList();
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
        assertThat(statuses.stream().filter(status -> status == 409)).hasSize(2);
    }

    @Test
    void invalidDocumentIsRefusedWhenSaving() {
        ApiClient author = newAuthor();
        String chapter = books.createChapter(author, books.createBook(author), "Capítulo");
        long version = author.get(draftPath(chapter)).json().at("/version").asLong();
        Map<String, Object> script = body("type", "doc", "content", List.of(body("type", "script")));

        ApiClient.Response response = author.put(draftPath(chapter), body("version", version, "document", script));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("INVALID_DOCUMENT");
    }

    @Test
    void publishedHtmlEscapesContentFromTheDraft() {
        ApiClient author = newAuthor();
        String chapter = books.createChapter(author, books.createBook(author), "Capítulo");

        books.publishChapter(author, chapter, "<script>alert(1)</script>");

        assertThat(publishedHtml(chapter)).doesNotContain("<script").contains("&lt;script&gt;");
    }

    @Test
    void publishingRequiresTheVersionTheAuthorSaw() {
        ApiClient author = newAuthor();
        String chapter = books.createChapter(author, books.createBook(author), "Capítulo");
        long seen = books.writeDraft(author, chapter, "Versão vista");
        books.writeDraft(author, chapter, "Versão de outra aba");

        ApiClient.Response response = author.post(publishPath(chapter), body("draftVersion", seen));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("DRAFT_CONFLICT");
        assertThat(revisionCount(chapter)).isZero();
    }

    @Test
    void emptyChapterCannotBePublished() {
        ApiClient author = newAuthor();
        String chapter = books.createChapter(author, books.createBook(author), "Capítulo");
        long version = author.get(draftPath(chapter)).json().at("/version").asLong();

        ApiClient.Response response = author.post(publishPath(chapter), body("draftVersion", version));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("EMPTY_CHAPTER");
    }

    @Test
    void republishingUnchangedTextCreatesNoNewRevisionButChangedTextDoes() {
        ApiClient author = newAuthor();
        String chapter = books.createChapter(author, books.createBook(author), "Capítulo");
        long version = books.writeDraft(author, chapter, "Versão 1");

        assertThat(author.post(publishPath(chapter), body("draftVersion", version)).json().at("/state").asString())
                .isEqualTo("PUBLISHED");
        assertThat(author.post(publishPath(chapter), body("draftVersion", version)).status()).isEqualTo(200);
        assertThat(revisionCount(chapter)).isEqualTo(1);

        assertThat(author.post("/api/v1/chapters/" + chapter + "/unpublish", body()).json().at("/state").asString())
                .isEqualTo("UNPUBLISHED");
        assertThat(author.post(publishPath(chapter), body("draftVersion", version)).json().at("/state").asString())
                .isEqualTo("PUBLISHED");
        assertThat(revisionCount(chapter)).isEqualTo(1);

        books.publishChapter(author, chapter, "Versão 2");
        assertThat(revisionCount(chapter)).isEqualTo(2);
        assertThat(publishedHtml(chapter)).isEqualTo("<p>Versão 2</p>");

        ApiClient.Response revisions = author.get("/api/v1/chapters/" + chapter + "/revisions");
        assertThat(revisions.json().at("/totalElements").asLong()).isEqualTo(2);
        assertThat(revisions.json().at("/content/0/revisionNumber").asInt()).isEqualTo(2);
        assertThat(revisions.json().at("/content/0/published").asBoolean()).isTrue();
        assertThat(revisions.json().at("/content/1/published").asBoolean()).isFalse();
    }

    @Test
    void simultaneousPublicationsNumberRevisionsWithoutConflict() {
        ApiClient author = newAuthor();
        String chapter = books.createChapter(author, books.createBook(author), "Capítulo");
        long version = books.writeDraft(author, chapter, "Texto");

        List<CompletableFuture<ApiClient.Response>> requests = List.of(
                CompletableFuture.supplyAsync(() -> author.post(publishPath(chapter), body("draftVersion", version))),
                CompletableFuture.supplyAsync(() -> author.post(publishPath(chapter), body("draftVersion", version))),
                CompletableFuture.supplyAsync(() -> author.post(publishPath(chapter), body("draftVersion", version))));

        assertThat(requests).allSatisfy(request -> assertThat(request.join().status()).isEqualTo(200));
        assertThat(revisionCount(chapter)).isEqualTo(1);
    }

    @Test
    void bookNeedsAPublishedChapterAndCannotLoseItsLastOne() {
        ApiClient author = newAuthor();
        String book = books.createBook(author);
        String chapter = books.createChapter(author, book, "Capítulo");

        ApiClient.Response empty = author.post("/api/v1/books/" + book + "/publish", body());
        assertThat(empty.status()).isEqualTo(409);
        assertThat(empty.code()).isEqualTo("BOOK_WITHOUT_PUBLISHED_CHAPTERS");

        books.publishChapter(author, chapter, "Texto");
        ApiClient.Response published = author.post("/api/v1/books/" + book + "/publish", body());
        assertThat(published.json().at("/publicationState").asString()).isEqualTo("PUBLISHED");

        ApiClient.Response last = author.post("/api/v1/chapters/" + chapter + "/unpublish", body());
        assertThat(last.status()).isEqualTo(409);
        assertThat(last.code()).isEqualTo("LAST_PUBLISHED_CHAPTER");

        ApiClient.Response unpublished = author.post("/api/v1/books/" + book + "/unpublish", body());
        assertThat(unpublished.json().at("/publicationState").asString()).isEqualTo("UNPUBLISHED");
        assertThat(author.post("/api/v1/chapters/" + chapter + "/unpublish", body()).status()).isEqualTo(200);
    }

    @Test
    void publishedChapterMustBeUnpublishedBeforeDeletion() {
        ApiClient author = newAuthor();
        String book = books.createBook(author);
        String chapter = books.createChapter(author, book, "Capítulo");
        books.publishChapter(author, chapter, "Texto");

        assertThat(author.delete("/api/v1/chapters/" + chapter).code()).isEqualTo("CHAPTER_PUBLISHED");
        author.post("/api/v1/chapters/" + chapter + "/unpublish", body());
        assertThat(author.delete("/api/v1/chapters/" + chapter).status()).isEqualTo(204);
        assertThat(author.get(draftPath(chapter)).status()).isEqualTo(404);
    }

    @Test
    void volumesOrganizeChaptersAndOnlyEmptyOnesCanBeDeleted() {
        ApiClient author = newAuthor();
        String book = books.createBook(author);
        ApiClient.Response volume = author.post("/api/v1/books/" + book + "/volumes", body("title", "Volume 1"));
        String volumeId = volume.json().at("/id").asString();
        assertThat(volume.json().at("/displayOrder").asInt()).isEqualTo(1);

        ApiClient.Response inVolume = author.post("/api/v1/books/" + book + "/chapters", body("title", "Dentro", "volumeId", volumeId));
        ApiClient.Response loose = author.post("/api/v1/books/" + book + "/chapters", body("title", "Solto"));
        assertThat(inVolume.json().at("/volumeId").asString()).isEqualTo(volumeId);
        assertThat(inVolume.json().at("/displayOrder").asInt()).isEqualTo(1);
        assertThat(loose.json().at("/volumeId").isNull()).isTrue();
        assertThat(loose.json().at("/displayOrder").asInt()).isEqualTo(1);

        assertThat(author.patch("/api/v1/volumes/" + volumeId, body("title", "Renomeado")).json().at("/title").asString())
                .isEqualTo("Renomeado");
        assertThat(author.delete("/api/v1/volumes/" + volumeId).code()).isEqualTo("VOLUME_NOT_EMPTY");
        assertThat(author.delete("/api/v1/chapters/" + inVolume.json().at("/id").asString()).status()).isEqualTo(204);
        assertThat(author.delete("/api/v1/volumes/" + volumeId).status()).isEqualTo(204);
    }

    @Test
    void reorderingSwapsPositionsAndRequiresTheCompleteList() {
        ApiClient author = newAuthor();
        String book = books.createBook(author);
        String first = author.post("/api/v1/books/" + book + "/volumes", body("title", "A")).json().at("/id").asString();
        String second = author.post("/api/v1/books/" + book + "/volumes", body("title", "B")).json().at("/id").asString();
        String path = "/api/v1/books/" + book + "/volumes/order";

        ApiClient.Response swapped = author.put(path, body("volumeIds", List.of(second, first)));

        assertThat(swapped.status()).isEqualTo(200);
        assertThat(swapped.json().at("/0/id").asString()).isEqualTo(second);
        assertThat(swapped.json().at("/0/displayOrder").asInt()).isEqualTo(1);
        assertThat(author.put(path, body("volumeIds", List.of(first))).code()).isEqualTo("INVALID_ORDER");
        assertThat(author.put(path, body("volumeIds", List.of(first, first))).code()).isEqualTo("INVALID_ORDER");
    }

    @Test
    void chaptersCanBeSwappedAndMovedBetweenVolumes() {
        ApiClient author = newAuthor();
        String book = books.createBook(author);
        String volume = author.post("/api/v1/books/" + book + "/volumes", body("title", "V")).json().at("/id").asString();
        String a = books.createChapter(author, book, "A");
        String b = books.createChapter(author, book, "B");
        String path = "/api/v1/books/" + book + "/chapters/order";

        ApiClient.Response swapped = author.put(path, body("chapterIds", List.of(b, a)));
        assertThat(swapped.status()).isEqualTo(200);
        assertThat(swapped.json().at("/0/id").asString()).isEqualTo(b);
        assertThat(swapped.json().at("/1/displayOrder").asInt()).isEqualTo(2);

        assertThat(author.put(path, body("chapterIds", List.of(a))).code()).isEqualTo("INVALID_ORDER");

        ApiClient.Response moved = author.put(path, body("volumeId", volume, "chapterIds", List.of(a)));
        assertThat(moved.json().at("/0/volumeId").asString()).isEqualTo(volume);

        ApiClient.Response contents = author.get("/api/v1/books/" + book + "/contents");
        assertThat(contents.json().at("/volumes").size()).isEqualTo(1);
        assertThat(contents.json().at("/chapters").size()).isEqualTo(2);
    }

    @Test
    void identifiersFromAnotherBookAreRejected() {
        ApiClient author = newAuthor();
        String book = books.createBook(author);
        String otherBook = books.createBook(author);
        String foreignChapter = books.createChapter(author, otherBook, "Alheio");
        String foreignVolume = author.post("/api/v1/books/" + otherBook + "/volumes", body("title", "Alheio")).json().at("/id").asString();

        assertThat(author.post("/api/v1/books/" + book + "/chapters", body("title", "Novo", "volumeId", foreignVolume)).code())
                .isEqualTo("VOLUME_NOT_IN_BOOK");
        assertThat(author.put("/api/v1/books/" + book + "/chapters/order", body("chapterIds", List.of(foreignChapter))).code())
                .isEqualTo("INVALID_ORDER");
        assertThat(author.put("/api/v1/books/" + book + "/volumes/order", body("volumeIds", List.of(foreignVolume))).code())
                .isEqualTo("INVALID_ORDER");
    }

    @Test
    void anotherUsersPrivateContentLooksLikeItDoesNotExist() {
        ApiClient owner = newAuthor();
        String book = books.createBook(owner);
        String chapter = books.createChapter(owner, book, "Secreto");
        books.writeDraft(owner, chapter, "Rascunho secreto");
        String volume = owner.post("/api/v1/books/" + book + "/volumes", body("title", "V")).json().at("/id").asString();
        ApiClient other = newAuthor();

        assertThat(other.get(draftPath(chapter)).status()).isEqualTo(404);
        assertThat(other.get(draftPath(chapter)).body()).doesNotContain("secreto");
        assertThat(other.put(draftPath(chapter), body("version", 0, "document", document("Invadido"))).status()).isEqualTo(404);
        assertThat(other.post(publishPath(chapter), body("draftVersion", 0)).status()).isEqualTo(404);
        assertThat(other.patch("/api/v1/chapters/" + chapter, body("title", "Invadido")).status()).isEqualTo(404);
        assertThat(other.delete("/api/v1/chapters/" + chapter).status()).isEqualTo(404);
        assertThat(other.get("/api/v1/chapters/" + chapter + "/revisions").status()).isEqualTo(404);
        assertThat(other.get("/api/v1/books/" + book + "/contents").status()).isEqualTo(404);
        assertThat(other.post("/api/v1/books/" + book + "/chapters", body("title", "Invasor")).status()).isEqualTo(404);
        assertThat(other.post("/api/v1/books/" + book + "/publish", body()).status()).isEqualTo(404);
        assertThat(other.patch("/api/v1/volumes/" + volume, body("title", "Invadido")).status()).isEqualTo(404);
        assertThat(other.delete("/api/v1/volumes/" + volume).status()).isEqualTo(404);
        assertThat(accounts.client().get(draftPath(chapter)).status()).isEqualTo(401);
    }

    @Test
    void publicContentOfAnotherUserGivesForbiddenWhenChanging() {
        ApiClient owner = newAuthor();
        String book = books.publishedBook(owner);
        String chapter = owner.get("/api/v1/books/" + book + "/contents").json().at("/chapters/0/id").asString();
        ApiClient other = newAuthor();

        assertThat(other.patch("/api/v1/chapters/" + chapter, body("title", "Invadido")).status()).isEqualTo(403);
        assertThat(other.post("/api/v1/chapters/" + chapter + "/unpublish", body()).status()).isEqualTo(403);
        assertThat(other.post("/api/v1/books/" + book + "/unpublish", body()).status()).isEqualTo(403);
        assertThat(other.post("/api/v1/books/" + book + "/chapters", body("title", "Invasor")).status()).isEqualTo(403);
    }

    @Test
    void adminAndRootCanManageContentAndTheRevisionRecordsWhoPublished() {
        ApiClient owner = newAuthor();
        String book = books.createBook(owner);
        String chapter = books.createChapter(owner, book, "Capítulo");

        for (String role : List.of("ADMIN", "ROOT")) {
            ApiClient privileged = withRole(role);
            String username = privileged.get("/api/v1/users/me").json().at("/username").asString();

            books.publishChapter(privileged, chapter, "Editado por " + role);

            assertThat(privileged.get("/api/v1/books/" + book + "/contents").status()).isEqualTo(200);
            assertThat(privileged.patch("/api/v1/chapters/" + chapter, body("title", "Título " + role)).status()).isEqualTo(200);
            assertThat(owner.get("/api/v1/chapters/" + chapter + "/revisions").json()
                    .at("/content/0/createdByUsername").asString()).isEqualTo(username);
        }

        String volume = owner.post("/api/v1/books/" + book + "/volumes", body("title", "V")).json().at("/id").asString();
        assertThat(withRole("ADMIN").delete("/api/v1/volumes/" + volume).status()).isEqualTo(204);
    }

    @Test
    void moderatorHasNoAccessToPrivateContent() {
        ApiClient owner = newAuthor();
        String chapter = books.createChapter(owner, books.createBook(owner), "Capítulo");

        assertThat(withRole("MODERATOR").get(draftPath(chapter)).status()).isEqualTo(404);
    }
}
