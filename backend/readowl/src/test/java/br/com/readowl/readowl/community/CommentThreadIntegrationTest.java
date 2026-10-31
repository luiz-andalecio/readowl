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
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class CommentThreadIntegrationTest {

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

    private static String bookComments(String book) {
        return "/api/v1/books/" + book + "/comments";
    }

    private static String replies(String comment) {
        return "/api/v1/comments/" + comment + "/replies";
    }

    private static String like(String comment) {
        return "/api/v1/comments/" + comment + "/like";
    }

    private static String post(ApiClient user, String path, String content) {
        ApiClient.Response created = user.post(path, body("content", content));
        assertThat(created.status()).isEqualTo(201);
        return created.json().at("/id").asString();
    }

    private JsonNode firstOnBook(String book) {
        return accounts.client().get(bookComments(book)).json().at("/content/0");
    }

    @Test
    void repliesStayOutOfTheMainListAndInheritTheTarget() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = author.get("/api/v1/books/" + book + "/contents").json().at("/chapters/0/id").asString();
        ApiClient reader = newUser();
        String main = post(reader, "/api/v1/chapters/" + chapter + "/comments", "Principal");
        ApiClient other = newUser();

        ApiClient.Response created = other.post(replies(main), body("content", "Resposta", "chapterId", UUID.randomUUID(),
                "bookId", UUID.randomUUID(), "parentId", UUID.randomUUID()));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().at("/parentId").asString()).isEqualTo(main);
        assertThat(created.json().at("/chapterId").asString()).isEqualTo(chapter);
        assertThat(created.json().at("/bookId").asString()).isEqualTo(book);

        ApiClient anonymous = accounts.client();
        JsonNode list = anonymous.get("/api/v1/chapters/" + chapter + "/comments").json();
        assertThat(list.at("/totalElements").asLong()).isEqualTo(1);
        assertThat(list.at("/content/0/id").asString()).isEqualTo(main);
        assertThat(list.at("/content/0/parentId").isNull()).isTrue();
        assertThat(list.at("/content/0/replyCount").asLong()).isEqualTo(1);
        ApiClient.Response thread = anonymous.get(replies(main));
        assertThat(thread.setCookie("SESSION")).isEmpty();
        assertThat(thread.json().at("/totalElements").asLong()).isEqualTo(1);
        assertThat(thread.json().at("/content/0/content").asString()).isEqualTo("Resposta");
    }

    @Test
    void repliesAreOldestFirstByDefaultAndPaginated() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = newUser();
        String main = post(reader, bookComments(book), "Principal");
        post(reader, replies(main), "Primeira");
        post(reader, replies(main), "Segunda");
        ApiClient anonymous = accounts.client();

        assertThat(anonymous.get(replies(main) + "?size=1").json().at("/content/0/content").asString()).isEqualTo("Primeira");
        assertThat(anonymous.get(replies(main) + "?sort=-createdAt").json().at("/content/0/content").asString()).isEqualTo("Segunda");
        assertThat(anonymous.get(replies(main) + "?sort=content").code()).isEqualTo("INVALID_SORT");
    }

    @Test
    void repliesHaveOnlyOneLevel() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = newUser();
        String main = post(reader, bookComments(book), "Principal");
        String reply = post(reader, replies(main), "Resposta");

        assertThat(reader.post(replies(reply), body("content", "Resposta da resposta")).code()).isEqualTo("NESTED_REPLY");
        assertThat(accounts.client().get(replies(reply)).code()).isEqualTo("COMMENT_NOT_FOUND");
    }

    @Test
    void writingRepliesAndLikesRequiresLoginAndCsrf() {
        String any = UUID.randomUUID().toString();
        ApiClient anonymous = accounts.client();

        assertThat(anonymous.post(replies(any), body("content", "Oi")).status()).isEqualTo(401);
        assertThat(anonymous.put(like(any), body()).status()).isEqualTo(401);
        assertThat(anonymous.delete(like(any)).status()).isEqualTo(401);
        assertThat(anonymous.get("/api/v1/users/me/liked-comments?commentIds=" + any).status()).isEqualTo(401);
        assertThat(newUser().postWithoutCsrf(replies(any), body("content", "Oi")).status()).isEqualTo(403);
    }

    @Test
    void replyContentFollowsTheSameRulesAsComments() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = newUser();
        String main = post(reader, bookComments(book), "Principal");

        assertThat(reader.post(replies(main), body("content", "  ")).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(reader.post(replies(main), body("content", "x\u0000y")).code()).isEqualTo("INVALID_COMMENT");
        assertThat(reader.post(replies(UUID.randomUUID().toString()), body("content", "Oi")).code()).isEqualTo("COMMENT_NOT_FOUND");

        post(reader, replies(main), "Repetida");
        assertThat(reader.post(replies(main), body("content", "Repetida")).code()).isEqualTo("DUPLICATE_COMMENT");
        // O mesmo texto em outro lugar não é repetição.
        post(reader, bookComments(book), "Repetida");
        String other = post(reader, bookComments(book), "Outro principal");
        post(reader, replies(other), "Repetida");
    }

    @Test
    void repliesCountForTheCommentLimit() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = newUser();
        String main = post(reader, bookComments(book), "Principal");

        // A janela é fixa: se virar durante o teste, uma das duas ainda passa do limite de 10.
        ApiClient.Response last = null;
        for (int attempt = 0; attempt < 21; attempt++) {
            last = reader.post(replies(main), body("content", "Resposta " + attempt));
            if (last.status() == 429) {
                break;
            }
        }

        assertThat(last.code()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void deletedCommentWithRepliesStaysAsAPlaceholderWithoutItsData() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String main = post(owner, bookComments(book), "Texto que sai");
        post(newUser(), replies(main), "Resposta que fica");

        assertThat(owner.delete("/api/v1/comments/" + main).status()).isEqualTo(204);

        ApiClient anonymous = accounts.client();
        ApiClient.Response list = anonymous.get(bookComments(book));
        assertThat(list.json().at("/totalElements").asLong()).isEqualTo(1);
        JsonNode placeholder = list.json().at("/content/0");
        assertThat(placeholder.at("/removed").asBoolean()).isTrue();
        assertThat(placeholder.at("/content").isNull()).isTrue();
        assertThat(placeholder.at("/author").isNull()).isTrue();
        assertThat(placeholder.at("/replyCount").asLong()).isEqualTo(1);
        assertThat(list.body()).doesNotContain("Texto que sai");
        assertThat(anonymous.get(replies(main)).json().at("/content/0/content").asString()).isEqualTo("Resposta que fica");
        assertThat(owner.post(replies(main), body("content", "Depois")).code()).isEqualTo("COMMENT_NOT_FOUND");
    }

    @Test
    void placeholderDisappearsWhenItsLastReplyGoes() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String main = post(owner, bookComments(book), "Principal");
        ApiClient replier = newUser();
        String reply = post(replier, replies(main), "Resposta");
        owner.delete("/api/v1/comments/" + main);

        assertThat(replier.delete("/api/v1/comments/" + reply).status()).isEqualTo(204);

        assertThat(accounts.client().get(bookComments(book)).json().at("/totalElements").asLong()).isZero();
    }

    @Test
    void deletedCommentWithoutRepliesLeavesNoPlaceholder() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        owner.delete("/api/v1/comments/" + post(owner, bookComments(book), "Sozinho"));

        assertThat(accounts.client().get(bookComments(book)).json().at("/totalElements").asLong()).isZero();
    }

    @Test
    void suspendedAccountsLeaveThreadsConsistent() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String ownerEmail = uniqueEmail();
        accounts.registerVerified(ownerEmail);
        ApiClient owner = accounts.loggedIn(ownerEmail, PASSWORD);
        String replierEmail = uniqueEmail();
        accounts.registerVerified(replierEmail);
        ApiClient replier = accounts.loggedIn(replierEmail, PASSWORD);
        String main = post(owner, bookComments(book), "Principal");
        post(replier, replies(main), "Resposta");

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", replierEmail);
        assertThat(firstOnBook(book).at("/replyCount").asLong()).isZero();
        assertThat(accounts.client().get(replies(main)).json().at("/totalElements").asLong()).isZero();

        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE email = ?", replierEmail);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", ownerEmail);
        JsonNode placeholder = firstOnBook(book);
        assertThat(placeholder.at("/removed").asBoolean()).isTrue();
        assertThat(placeholder.at("/author").isNull()).isTrue();
        assertThat(placeholder.at("/replyCount").asLong()).isEqualTo(1);
    }

    @Test
    void threadsOfContentOutOfTheAirLookMissing() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = newUser();
        String main = post(reader, bookComments(book), "Principal");
        author.post("/api/v1/books/" + book + "/unpublish", body());

        assertThat(accounts.client().get(replies(main)).code()).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(newUser().post(replies(main), body("content", "Oi")).code()).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(newUser().put(like(main), body()).code()).isEqualTo("COMMENT_NOT_FOUND");
    }

    @Test
    void likesCountOncePerUserAndCanBeRemoved() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String main = post(owner, bookComments(book), "Principal");
        ApiClient first = newUser();
        ApiClient second = newUser();

        ApiClient.Response liked = first.put(like(main), body());
        assertThat(liked.status()).isEqualTo(200);
        assertThat(liked.json().at("/liked").asBoolean()).isTrue();
        assertThat(liked.json().at("/likeCount").asLong()).isEqualTo(1);
        assertThat(first.put(like(main), body()).json().at("/likeCount").asLong()).isEqualTo(1);
        assertThat(second.put(like(main), body()).json().at("/likeCount").asLong()).isEqualTo(2);
        assertThat(firstOnBook(book).at("/likeCount").asLong()).isEqualTo(2);

        assertThat(first.delete(like(main)).status()).isEqualTo(204);
        assertThat(first.delete(like(main)).status()).isEqualTo(204);
        assertThat(firstOnBook(book).at("/likeCount").asLong()).isEqualTo(1);
    }

    @Test
    void repliesCanBeLikedToo() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = newUser();
        String main = post(reader, bookComments(book), "Principal");
        String reply = post(reader, replies(main), "Resposta");

        newUser().put(like(reply), body());

        assertThat(accounts.client().get(replies(main)).json().at("/content/0/likeCount").asLong()).isEqualTo(1);
        assertThat(firstOnBook(book).at("/likeCount").asLong()).isZero();
    }

    @Test
    void ownCommentsAndForgedValuesAreRefused() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String main = post(owner, bookComments(book), "Principal");

        assertThat(owner.put(like(main), body()).code()).isEqualTo("OWN_COMMENT_LIKE");
        assertThat(newUser().put(like(main), body("likeCount", 1000, "userId", UUID.randomUUID())).json().at("/likeCount").asLong()).isEqualTo(1);
        assertThat(newUser().put(like(UUID.randomUUID().toString()), body()).code()).isEqualTo("COMMENT_NOT_FOUND");
    }

    @Test
    void simultaneousLikesCountOnce() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String main = post(newUser(), bookComments(book), "Principal");
        ApiClient reader = newUser();

        List<CompletableFuture<ApiClient.Response>> requests = IntStream.range(0, 10)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> reader.put(like(main), body())))
                .toList();

        assertThat(requests).allSatisfy(request -> assertThat(request.join().status()).isEqualTo(200));
        assertThat(firstOnBook(book).at("/likeCount").asLong()).isEqualTo(1);
    }

    @Test
    void personalLikesAreSeparateFromThePublicLists() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String first = post(owner, bookComments(book), "Um");
        String second = post(owner, bookComments(book), "Dois");
        ApiClient reader = newUser();
        reader.put(like(second), body());
        String path = "/api/v1/users/me/liked-comments?commentIds=" + first + "," + second;

        JsonNode liked = reader.get(path).json().at("/commentIds");
        assertThat(liked.size()).isEqualTo(1);
        assertThat(liked.get(0).asString()).isEqualTo(second);
        assertThat(newUser().get(path).json().at("/commentIds").size()).isZero();
        assertThat(reader.get(bookComments(book)).body()).doesNotContain("\"liked\"");

        List<String> tooMany = new ArrayList<>();
        IntStream.range(0, 51).forEach(i -> tooMany.add(UUID.randomUUID().toString()));
        assertThat(reader.get("/api/v1/users/me/liked-comments?commentIds=" + tooMany.stream().collect(Collectors.joining(",")))
                .code()).isEqualTo("TOO_MANY_IDS");
    }

    @Test
    void likesOfSuspendedAccountsStopCounting() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String main = post(newUser(), bookComments(book), "Principal");
        String email = uniqueEmail();
        accounts.registerVerified(email);
        accounts.loggedIn(email, PASSWORD).put(like(main), body());

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        assertThat(firstOnBook(book).at("/likeCount").asLong()).isZero();
    }

    @Test
    void moderationSeesRepliesAndHidingKeepsTheConversation() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String main = post(owner, bookComments(book), "Ofensa");
        String reply = post(newUser(), replies(main), "Resposta");
        String moderatorEmail = uniqueEmail();
        accounts.registerVerified(moderatorEmail);
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE email = ?", moderatorEmail);
        ApiClient moderator = accounts.loggedIn(moderatorEmail, PASSWORD);

        assertThat(moderator.post("/api/v1/moderation/comments/" + main + "/hide", body("reason", "Ofensa")).status()).isEqualTo(204);

        JsonNode placeholder = firstOnBook(book);
        assertThat(placeholder.at("/removed").asBoolean()).isTrue();
        assertThat(placeholder.toString()).doesNotContain("Ofensa");
        ApiClient.Response all = moderator.get("/api/v1/moderation/comments?bookId=" + book);
        assertThat(all.json().at("/totalElements").asLong()).isEqualTo(2);
        assertThat(all.body()).contains("\"parentId\":\"" + main + "\"").contains(reply).contains("Ofensa");

        assertThat(moderator.post("/api/v1/moderation/comments/" + main + "/restore", body("reason", "Engano")).status()).isEqualTo(204);
        assertThat(firstOnBook(book).at("/content").asString()).isEqualTo("Ofensa");
    }
}
