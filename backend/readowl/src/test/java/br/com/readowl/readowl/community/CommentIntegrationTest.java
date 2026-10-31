package br.com.readowl.readowl.community;

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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class CommentIntegrationTest {

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

    private ApiClient newUser() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private ApiClient newUserWithRole(String role) {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = ? WHERE email = ?", role, email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private static String bookComments(String bookId) {
        return "/api/v1/books/" + bookId + "/comments";
    }

    private static String chapterComments(String chapterId) {
        return "/api/v1/chapters/" + chapterId + "/comments";
    }

    private static String comment(String id) {
        return "/api/v1/comments/" + id;
    }

    private static String firstChapter(ApiClient author, String bookId) {
        return author.get("/api/v1/books/" + bookId + "/contents").json().at("/chapters/0/id").asString();
    }

    private static String post(ApiClient user, String path, String content) {
        ApiClient.Response created = user.post(path, body("content", content));
        assertThat(created.status()).isEqualTo(201);
        return created.json().at("/id").asString();
    }

    @Test
    void writingRequiresLoginAndCsrfButReadingIsPublic() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient anonymous = accounts.client();

        assertThat(anonymous.post(bookComments(book), body("content", "Oi")).status()).isEqualTo(401);
        assertThat(anonymous.post(chapterComments(chapter), body("content", "Oi")).status()).isEqualTo(401);
        assertThat(anonymous.patch(comment(UUID.randomUUID().toString()), body("content", "Oi")).status()).isEqualTo(401);
        assertThat(anonymous.delete(comment(UUID.randomUUID().toString())).status()).isEqualTo(401);
        assertThat(newUser().postWithoutCsrf(bookComments(book), body("content", "Oi")).status()).isEqualTo(403);

        ApiClient.Response list = anonymous.get(bookComments(book));
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.setCookie("SESSION")).isEmpty();
        assertThat(anonymous.get(chapterComments(chapter)).status()).isEqualTo(200);
    }

    @Test
    void commentsOnBooksAndChaptersAreSeparateAndShowOnlyPublicAuthorData() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        String email = uniqueEmail();
        String username = accounts.registerVerified(email);
        ApiClient reader = accounts.loggedIn(email, PASSWORD);

        ApiClient.Response created = reader.post(bookComments(book), body("content", "  Gostei da obra  "));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().at("/content").asString()).isEqualTo("Gostei da obra");
        assertThat(created.json().at("/bookId").asString()).isEqualTo(book);
        assertThat(created.json().at("/chapterId").isNull()).isTrue();
        assertThat(created.json().at("/author/username").asString()).isEqualTo(username);
        assertThat(created.json().at("/edited").asBoolean()).isFalse();
        assertThat(created.body()).doesNotContain(email).doesNotContain("passwordHash").doesNotContain("deletedAt");

        post(reader, chapterComments(chapter), "Gostei do capítulo");

        ApiClient anonymous = accounts.client();
        ApiClient.Response onBook = anonymous.get(bookComments(book));
        assertThat(onBook.json().at("/totalElements").asLong()).isEqualTo(1);
        assertThat(onBook.json().at("/content/0/content").asString()).isEqualTo("Gostei da obra");
        ApiClient.Response onChapter = anonymous.get(chapterComments(chapter));
        assertThat(onChapter.json().at("/totalElements").asLong()).isEqualTo(1);
        assertThat(onChapter.json().at("/content/0/content").asString()).isEqualTo("Gostei do capítulo");
        assertThat(onChapter.json().at("/content/0/chapterId").asString()).isEqualTo(chapter);
    }

    @Test
    void listIsPaginatedAndAcceptsOnlyTheAllowedSort() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = newUser();
        post(reader, bookComments(book), "Primeiro");
        post(reader, bookComments(book), "Segundo");
        post(reader, bookComments(book), "Terceiro");
        ApiClient anonymous = accounts.client();

        ApiClient.Response newest = anonymous.get(bookComments(book) + "?size=2");
        assertThat(newest.json().at("/content/0/content").asString()).isEqualTo("Terceiro");
        assertThat(newest.json().at("/content").size()).isEqualTo(2);
        assertThat(newest.json().at("/totalPages").asInt()).isEqualTo(2);
        assertThat(anonymous.get(bookComments(book) + "?sort=createdAt").json().at("/content/0/content").asString())
                .isEqualTo("Primeiro");

        assertThat(anonymous.get(bookComments(book) + "?sort=content").code()).isEqualTo("INVALID_SORT");
        assertThat(anonymous.get(bookComments(book) + "?size=51").code()).isEqualTo("INVALID_PAGINATION");
    }

    @Test
    void contentIsValidatedAndStoredWithoutInterpretation() {
        String book = books.publishedBook(newUser());
        ApiClient reader = newUser();

        assertThat(reader.post(bookComments(book), body("content", "   ")).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(reader.post(bookComments(book), body()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(reader.post(bookComments(book), body("content", "a".repeat(5001))).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(reader.post(bookComments(book), body("content", "nulo\u0000aqui")).code()).isEqualTo("INVALID_COMMENT");
        assertThat(reader.post(bookComments(book), body("content", "a".repeat(5000))).status()).isEqualTo(201);

        String script = "<script>alert('xss')</script>\nSegunda linha";
        ApiClient.Response created = reader.post(bookComments(book), body("content", script));
        assertThat(created.json().at("/content").asString()).isEqualTo(script);
    }

    @Test
    void clientCannotForgeTheAuthorOrTheTarget() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String otherChapter = firstChapter(author, books.publishedBook(author));
        String email = uniqueEmail();
        String username = accounts.registerVerified(email);
        ApiClient reader = accounts.loggedIn(email, PASSWORD);

        ApiClient.Response created = reader.post(bookComments(book), body("content", "Oi",
                "userId", UUID.randomUUID(), "username", "outra", "chapterId", otherChapter, "parentId", UUID.randomUUID()));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().at("/author/username").asString()).isEqualTo(username);
        assertThat(created.json().at("/chapterId").isNull()).isTrue();
    }

    @Test
    void contentOutOfTheAirLooksLikeItDoesNotExist() {
        ApiClient author = newUser();
        String draftBook = books.createBook(author);
        String published = books.publishedBook(author);
        String draftChapter = books.createChapter(author, published, "Rascunho");
        ApiClient reader = newUser();

        assertThat(reader.get(bookComments(draftBook)).code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(reader.post(bookComments(draftBook), body("content", "Oi")).code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(reader.post(bookComments(UUID.randomUUID().toString()), body("content", "Oi")).code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(reader.get(chapterComments(draftChapter)).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(reader.post(chapterComments(draftChapter), body("content", "Oi")).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(author.post(bookComments(draftBook), body("content", "Oi")).code()).isEqualTo("BOOK_NOT_FOUND");
    }

    @Test
    void chapterOfAnUnpublishedBookIsNotPublic() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient reader = newUser();
        post(reader, chapterComments(chapter), "Oi");

        author.post("/api/v1/books/" + book + "/unpublish", body());

        assertThat(accounts.client().get(chapterComments(chapter)).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(reader.post(chapterComments(chapter), body("content", "De novo")).code()).isEqualTo("CHAPTER_NOT_FOUND");

        author.post("/api/v1/books/" + book + "/publish", body());
        assertThat(accounts.client().get(chapterComments(chapter)).json().at("/totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void ownerEditsAndOthersCannot() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String id = post(owner, bookComments(book), "Original");

        assertThat(newUser().patch(comment(id), body("content", "Invadido")).code()).isEqualTo("ACCESS_DENIED");
        assertThat(author.patch(comment(id), body("content", "Invadido")).code()).isEqualTo("ACCESS_DENIED");
        assertThat(owner.patch(comment(id), body("content", "   ")).code()).isEqualTo("VALIDATION_FAILED");

        ApiClient.Response edited = owner.patch(comment(id), body("content", "Corrigido"));
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.json().at("/content").asString()).isEqualTo("Corrigido");
        assertThat(edited.json().at("/edited").asBoolean()).isTrue();
        assertThat(accounts.client().get(bookComments(book)).json().at("/content/0/edited").asBoolean()).isTrue();
        assertThat(owner.patch(comment(UUID.randomUUID().toString()), body("content", "Oi")).code()).isEqualTo("COMMENT_NOT_FOUND");
    }

    @Test
    void administratorsHaveFullAccessIncludingOutOfTheAir() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String first = post(owner, bookComments(book), "Um");
        String second = post(owner, bookComments(book), "Dois");
        author.post("/api/v1/books/" + book + "/unpublish", body());

        ApiClient admin = newUserWithRole("ADMIN");
        ApiClient root = newUserWithRole("ROOT");
        assertThat(admin.patch(comment(first), body("content", "Moderado")).json().at("/content").asString()).isEqualTo("Moderado");
        assertThat(root.delete(comment(second)).status()).isEqualTo(204);
        assertThat(admin.delete(comment(first)).status()).isEqualTo(204);
    }

    @Test
    void outOfTheAirTheOwnerOnlyDeletesAndOthersSeeNothing() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String id = post(owner, bookComments(book), "Meu comentário");
        author.post("/api/v1/books/" + book + "/unpublish", body());

        assertThat(owner.patch(comment(id), body("content", "Novo")).code()).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(newUser().delete(comment(id)).code()).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(owner.delete(comment(id)).status()).isEqualTo(204);
    }

    @Test
    void deletionHidesTheCommentButKeepsItForModeration() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String id = post(owner, bookComments(book), "Excluir");

        assertThat(newUser().delete(comment(id)).code()).isEqualTo("ACCESS_DENIED");
        assertThat(author.delete(comment(id)).code()).isEqualTo("ACCESS_DENIED");
        assertThat(owner.delete(comment(id)).status()).isEqualTo(204);

        assertThat(accounts.client().get(bookComments(book)).json().at("/totalElements").asLong()).isZero();
        assertThat(owner.delete(comment(id)).code()).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(owner.patch(comment(id), body("content", "Voltei")).code()).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE id = ? AND deleted_at IS NOT NULL",
                String.class, UUID.fromString(id))).isEqualTo("Excluir");
    }

    @Test
    void repeatedTextIsRefusedOnlyOnTheSameTarget() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient reader = newUser();
        String first = post(reader, bookComments(book), "Amei!");

        assertThat(reader.post(bookComments(book), body("content", "  Amei!  ")).code()).isEqualTo("DUPLICATE_COMMENT");
        assertThat(reader.post(chapterComments(chapter), body("content", "Amei!")).status()).isEqualTo(201);
        assertThat(reader.post(chapterComments(chapter), body("content", "Amei!")).code()).isEqualTo("DUPLICATE_COMMENT");
        assertThat(newUser().post(bookComments(book), body("content", "Amei!")).status()).isEqualTo(201);

        reader.delete(comment(first));
        assertThat(reader.post(bookComments(book), body("content", "Amei!")).status()).isEqualTo(201);

        jdbc.update("UPDATE comments SET created_at = now() - interval '11 minutes' WHERE book_id = ? AND content = 'Amei!'",
                UUID.fromString(book));
        assertThat(reader.post(bookComments(book), body("content", "Amei!")).status()).isEqualTo(201);
    }

    @Test
    void simultaneousIdenticalCommentsCreateOnlyOne() {
        String book = books.publishedBook(newUser());
        ApiClient reader = newUser();

        List<CompletableFuture<ApiClient.Response>> requests = IntStream.range(0, 5)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> reader.post(bookComments(book), body("content", "Clique duplo"))))
                .toList();

        List<Integer> statuses = requests.stream().map(request -> request.join().status()).toList();
        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(status -> status == 201)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE book_id = ?", Integer.class,
                UUID.fromString(book))).isEqualTo(1);
    }

    @Test
    void tooManyCommentsAreLimitedPerAccount() {
        String book = books.publishedBook(newUser());
        ApiClient spammer = newUser();

        // A janela é fixa: se virar durante o teste, uma das duas ainda passa do limite de 10.
        ApiClient.Response last = null;
        for (int attempt = 0; attempt < 21; attempt++) {
            last = spammer.post(bookComments(book), body("content", "Mensagem " + attempt));
            if (last.status() == 429) {
                break;
            }
        }

        assertThat(last.status()).isEqualTo(429);
        assertThat(last.code()).isEqualTo("RATE_LIMITED");
        assertThat(last.headers().firstValue("Retry-After")).isPresent();
        assertThat(newUser().post(bookComments(book), body("content", "Outra conta")).status()).isEqualTo(201);
    }

    @Test
    void suspendedUsersCommentsDisappearAndComeBack() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient reader = accounts.loggedIn(email, PASSWORD);
        String id = post(reader, bookComments(book), "Comentário");

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        assertThat(accounts.client().get(bookComments(book)).json().at("/totalElements").asLong()).isZero();
        assertThat(newUser().patch(comment(id), body("content", "Oi")).code()).isEqualTo("COMMENT_NOT_FOUND");

        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE email = ?", email);
        assertThat(accounts.client().get(bookComments(book)).json().at("/totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void deletingAChapterOrBookRemovesItsComments() {
        ApiClient author = newUser();
        String title = "Obra excluível " + UUID.randomUUID();
        String book = books.publishedBook(author, books.newBook(title, BookFixtures.uniqueAcronym()));
        String chapter = books.createChapter(author, book, "Excluível");
        books.publishChapter(author, chapter, "Texto");
        ApiClient reader = newUser();
        post(reader, chapterComments(chapter), "No capítulo");
        post(reader, bookComments(book), "Na obra");

        author.post("/api/v1/chapters/" + chapter + "/unpublish", body());
        assertThat(author.delete("/api/v1/chapters/" + chapter).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE book_id = ?", Integer.class,
                UUID.fromString(book))).isEqualTo(1);

        author.post("/api/v1/books/" + book + "/unpublish", body());
        String confirm = URLEncoder.encode(title, StandardCharsets.UTF_8);
        assertThat(author.delete("/api/v1/books/" + book + "?confirmTitle=" + confirm).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE book_id = ?", Integer.class,
                UUID.fromString(book))).isZero();
    }
}
