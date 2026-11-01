package br.com.readowl.readowl.book;

import br.com.readowl.readowl.shared.text.Slugs;
import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import br.com.readowl.readowl.support.SqlFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static br.com.readowl.readowl.support.BookFixtures.uniqueAcronym;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ApiIntegrationTest
class BookIntegrationTest {

    private static final String BOOKS = "/api/v1/books";

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

    /**
     * Título único por teste: a sigla não basta, porque os dois formam o slug.
     */
    private static String uniqueTitle() {
        return "Obra " + UUID.randomUUID();
    }

    @Test
    void slugCombinesAcronymAndTitleWithoutAccents() {
        String acronym = uniqueAcronym();

        ApiClient.Response created = newAuthor().post(BOOKS, books.newBook("Shihais: Remanescente da Ação", acronym));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().at("/slug").asString())
                .isEqualTo(acronym.toLowerCase() + "-shihais-remanescente-da-acao");
        assertThat(created.json().at("/acronym").asString()).isEqualTo(acronym);
        assertThat(created.json().at("/publicationState").asString()).isEqualTo("DRAFT");
        assertThat(created.json().at("/status").asString()).isEqualTo("ONGOING");
    }

    @Test
    void acronymIsStoredInUppercase() {
        String acronym = uniqueAcronym().toLowerCase();

        ApiClient.Response created = newAuthor().post(BOOKS, books.newBook(uniqueTitle(), acronym));

        assertThat(created.json().at("/acronym").asString()).isEqualTo(acronym.toUpperCase());
    }

    @Test
    void sameTitleWithDifferentAcronymsIsAllowedButSameCombinationIsNot() {
        String title = uniqueTitle();
        String acronym = uniqueAcronym();
        ApiClient author = newAuthor();
        assertThat(author.post(BOOKS, books.newBook(title, acronym)).status()).isEqualTo(201);

        ApiClient.Response repeated = newAuthor().post(BOOKS, books.newBook(title, acronym));
        ApiClient.Response otherAcronym = newAuthor().post(BOOKS, books.newBook(title, uniqueAcronym()));

        assertThat(repeated.status()).isEqualTo(409);
        assertThat(repeated.code()).isEqualTo("BOOK_SLUG_TAKEN");
        assertThat(otherAcronym.status()).isEqualTo(201);
    }

    @Test
    void renamingOrChangingTheAcronymChangesTheSlug() {
        ApiClient author = newAuthor();
        String acronym = uniqueAcronym();
        String id = author.post(BOOKS, books.newBook("Título Antigo", acronym)).json().at("/id").asString();

        ApiClient.Response renamed = author.patch(BOOKS + "/" + id, body("title", "Título Novo"));
        assertThat(renamed.json().at("/slug").asString()).isEqualTo(acronym.toLowerCase() + "-titulo-novo");

        String otherAcronym = uniqueAcronym();
        ApiClient.Response reacronymed = author.patch(BOOKS + "/" + id, body("acronym", otherAcronym));
        assertThat(reacronymed.json().at("/slug").asString()).isEqualTo(otherAcronym.toLowerCase() + "-titulo-novo");
        assertThat(reacronymed.json().at("/title").asString()).isEqualTo("Título Novo");
    }

    @Test
    void renamingToAnOccupiedSlugIsRefusedAndChangesNothing() {
        String acronym = uniqueAcronym();
        ApiClient author = newAuthor();
        author.post(BOOKS, books.newBook("Ocupado", acronym));
        String id = author.post(BOOKS, books.newBook("Livre", acronym)).json().at("/id").asString();

        ApiClient.Response response = author.patch(BOOKS + "/" + id, body("title", "Ocupado"));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("BOOK_SLUG_TAKEN");
        assertThat(author.get(BOOKS + "/" + id + "/details").json().at("/title").asString()).isEqualTo("Livre");
    }

    @Test
    void simultaneousCreationWithTheSameSlugCreatesOneBook() throws Exception {
        String title = uniqueTitle();
        String acronym = uniqueAcronym();
        ApiClient first = newAuthor();
        ApiClient second = newAuthor();

        var a = CompletableFuture.supplyAsync(() -> first.post(BOOKS, books.newBook(title, acronym)));
        var b = CompletableFuture.supplyAsync(() -> second.post(BOOKS, books.newBook(title, acronym)));

        assertThat(List.of(a.get().status(), b.get().status())).containsExactlyInAnyOrder(201, 409);
    }

    @Test
    void simultaneousRenamesToTheSameSlugLeaveOneBookWithIt() {
        String acronym = uniqueAcronym();
        String target = uniqueTitle();
        ApiClient first = newAuthor();
        ApiClient second = newAuthor();
        String firstId = first.post(BOOKS, books.newBook(uniqueTitle(), acronym)).json().at("/id").asString();
        String secondId = second.post(BOOKS, books.newBook(uniqueTitle(), acronym)).json().at("/id").asString();

        var a = CompletableFuture.supplyAsync(() -> first.patch(BOOKS + "/" + firstId, body("title", target)));
        var b = CompletableFuture.supplyAsync(() -> second.patch(BOOKS + "/" + secondId, body("title", target)));

        assertThat(List.of(a.join().status(), b.join().status())).containsExactlyInAnyOrder(200, 409);
        assertThat(List.of(a.join().code(), b.join().code())).contains("BOOK_SLUG_TAKEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM books WHERE slug = ?", Integer.class,
                acronym.toLowerCase() + "-" + Slugs.slugify(target))).isEqualTo(1);
    }

    @Test
    void differentTitlesThatProduceTheSameSlugConflict() {
        String acronym = uniqueAcronym();
        String suffix = UUID.randomUUID().toString();
        ApiClient author = newAuthor();
        assertThat(author.post(BOOKS, books.newBook("Coração " + suffix, acronym)).status()).isEqualTo(201);

        ApiClient.Response response = author.post(BOOKS, books.newBook("CORACAO: " + suffix, acronym.toLowerCase()));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("BOOK_SLUG_TAKEN");
    }

    @Test
    void databaseRejectsDuplicateBookSlugEvenWithoutTheApplication() {
        UUID author = new SqlFixtures(jdbc).user();
        String slug = "slug-direto-" + UUID.randomUUID();
        String insert = """
                INSERT INTO books (id, version, created_at, updated_at, title, acronym, slug, synopsis, status,
                                   publication_state, author_id)
                VALUES (?, 0, now(), now(), 'Obra', 'TST', ?, 'Sinopse', 'ONGOING', 'DRAFT', ?)
                """;
        jdbc.update(insert, UUID.randomUUID(), slug, author);

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), slug, author))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsDuplicateChapterSlugInTheSameBookButAllowsItInAnother() {
        SqlFixtures fixtures = new SqlFixtures(jdbc);
        UUID author = fixtures.user();
        UUID book = fixtures.book(author);
        UUID otherBook = fixtures.book(author);
        String insert = """
                INSERT INTO chapters (id, version, created_at, updated_at, title, slug, state, display_order, book_id)
                VALUES (?, 0, now(), now(), 'Capitulo', 'mesmo-slug', 'DRAFT', ?, ?)
                """;
        jdbc.update(insert, UUID.randomUUID(), 1, book);
        jdbc.update(insert, UUID.randomUUID(), 1, otherBook);

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), 2, book))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void titleWithoutLettersFallsBackToTheAcronym() {
        String acronym = uniqueAcronym();

        ApiClient.Response created = newAuthor().post(BOOKS, books.newBook("???", acronym));

        assertThat(created.json().at("/slug").asString()).isEqualTo(acronym.toLowerCase());
    }

    @Test
    void validatesFields() {
        ApiClient author = newAuthor();
        UUID genre = books.genreId("Drama");
        Map<String, Object> base = books.newBook(uniqueTitle(), uniqueAcronym());

        assertThat(author.post(BOOKS, with(base, "acronym", "S R A")).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(author.post(BOOKS, with(base, "acronym", "A")).status()).isEqualTo(400);
        assertThat(author.post(BOOKS, with(base, "acronym", "AÇÃO")).status()).isEqualTo(400);
        assertThat(author.post(BOOKS, with(base, "title", " ")).status()).isEqualTo(400);
        assertThat(author.post(BOOKS, with(base, "synopsis", "x".repeat(2001))).status()).isEqualTo(400);
        assertThat(author.post(BOOKS, with(base, "genreIds", List.of())).status()).isEqualTo(400);
        assertThat(author.post(BOOKS, with(base, "genreIds", List.of(genre, genre, genre, genre, genre, genre))).status())
                .isEqualTo(400);
        assertThat(author.post(BOOKS, with(base, "genreIds", List.of(UUID.randomUUID()))).code()).isEqualTo("UNKNOWN_GENRE");
        assertThat(author.post(BOOKS, with(base, "tags", List.of("<script>"))).status()).isEqualTo(400);
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    @Test
    void tagsAreCreatedOnDemandWithoutDuplicatesByCase() {
        String tag = "Tag" + UUID.randomUUID().toString().substring(0, 8);
        ApiClient author = newAuthor();
        Map<String, Object> first = with(books.newBook(uniqueTitle(), uniqueAcronym()), "tags", List.of(tag, tag.toUpperCase()));

        ApiClient.Response created = author.post(BOOKS, first);
        ApiClient.Response second = author.post(BOOKS,
                with(books.newBook(uniqueTitle(), uniqueAcronym()), "tags", List.of(" " + tag.toLowerCase() + " ")));

        assertThat(created.json().at("/tags").size()).isEqualTo(1);
        assertThat(created.json().at("/tags/0/name").asString()).isEqualTo(tag);
        assertThat(second.json().at("/tags/0/name").asString()).isEqualTo(tag);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tags WHERE lower(name) = lower(?)", Integer.class, tag)).isEqualTo(1);
    }

    @Test
    void genresAndTagsArePublic() {
        String tag = "Busca" + UUID.randomUUID().toString().substring(0, 8);
        books.publishedBook(newAuthor(), with(books.newBook(uniqueTitle(), uniqueAcronym()), "tags", List.of(tag)));
        ApiClient anonymous = accounts.client();

        assertThat(anonymous.get("/api/v1/genres").json().size()).isGreaterThanOrEqualTo(55);
        ApiClient.Response found = anonymous.get("/api/v1/tags?query=" + tag.toLowerCase());
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.json().at("/0/name").asString()).isEqualTo(tag);
        assertThat(anonymous.get("/api/v1/tags?query=" + URLEncoder.encode("%", StandardCharsets.UTF_8)).json().size()).isZero();
    }

    @Test
    void creatingABookRequiresLogin() {
        assertThat(accounts.client().post(BOOKS, books.newBook(uniqueTitle(), uniqueAcronym())).status()).isEqualTo(401);
    }

    @Test
    void creatingABookRequiresCsrf() {
        assertThat(newAuthor().postWithoutCsrf(BOOKS, books.newBook(uniqueTitle(), uniqueAcronym())).code())
                .isEqualTo("CSRF_TOKEN_INVALID");
    }

    @Test
    void authorCannotBeForgedInTheRequest() {
        ApiClient author = newAuthor();
        ApiClient other = newAuthor();
        String otherUsername = other.get("/api/v1/users/me").json().at("/username").asString();
        Map<String, Object> forged = with(books.newBook(uniqueTitle(), uniqueAcronym()), "authorId", UUID.randomUUID());
        forged.put("authorUsername", otherUsername);
        forged.put("publicationState", "PUBLISHED");
        forged.put("viewCount", 1000);

        ApiClient.Response created = author.post(BOOKS, forged);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().at("/authorUsername").asString()).isNotEqualTo(otherUsername);
        assertThat(created.json().at("/publicationState").asString()).isEqualTo("DRAFT");
    }

    @Test
    void draftBookOfAnotherUserLooksLikeItDoesNotExist() {
        String id = books.createBook(newAuthor());
        ApiClient other = newAuthor();
        String path = BOOKS + "/" + id;

        assertThat(other.get(path + "/details").status()).isEqualTo(404);
        assertThat(other.get(path + "/details").code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(other.patch(path, body("title", "Invadido")).status()).isEqualTo(404);
    }

    @Test
    void publishedBookOfAnotherUserCannotBeEdited() {
        String id = books.publishedBook(newAuthor());
        ApiClient other = newAuthor();

        ApiClient.Response response = other.patch(BOOKS + "/" + id, body("title", "Invadido"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void moderatorsHaveNoAccessToOtherAuthorsBooks() {
        String id = books.createBook(newAuthor());

        assertThat(withRole("MODERATOR").patch(BOOKS + "/" + id, body("title", "Moderado")).status()).isEqualTo(404);
    }

    @Test
    void adminAndRootCanManageAnyBook() {
        ApiClient author = newAuthor();
        String authorUsername = author.get("/api/v1/users/me").json().at("/username").asString();
        String id = books.createBook(author);

        for (String role : List.of("ADMIN", "ROOT")) {
            ApiClient privileged = withRole(role);
            String title = role + " " + UUID.randomUUID();

            ApiClient.Response edited = privileged.patch(BOOKS + "/" + id, body("title", title));

            assertThat(edited.status()).isEqualTo(200);
            assertThat(edited.json().at("/title").asString()).isEqualTo(title);
            assertThat(edited.json().at("/authorUsername").asString()).isEqualTo(authorUsername);
            assertThat(privileged.get(BOOKS + "/" + id + "/details").status()).isEqualTo(200);
        }
    }

    @Test
    void adminCanDeleteAnotherAuthorsBook() {
        String title = uniqueTitle();
        String id = books.createBook(newAuthor(), title);

        ApiClient.Response deleted = withRole("ADMIN")
                .delete(BOOKS + "/" + id + "?confirmTitle=" + URLEncoder.encode(title, StandardCharsets.UTF_8));

        assertThat(deleted.status()).isEqualTo(204);
    }

    @Test
    void listingAllBooksIsForAdminAndRootOnly() {
        ApiClient author = newAuthor();
        String id = books.createBook(author);

        assertThat(author.get("/api/v1/admin/books").status()).isEqualTo(403);
        assertThat(accounts.client().get("/api/v1/admin/books").status()).isEqualTo(401);
        assertThat(withRole("MODERATOR").get("/api/v1/admin/books").status()).isEqualTo(403);
        assertThat(withRole("ROOT").get("/api/v1/admin/books?size=50&sort=-createdAt").body()).contains(id);
        assertThat(withRole("ADMIN").get("/api/v1/admin/books?size=50&sort=-createdAt").status()).isEqualTo(200);
    }

    @Test
    void myBooksListsOnlyMineWithPagination() {
        ApiClient author = newAuthor();
        String first = books.createBook(author, "B " + UUID.randomUUID());
        String second = books.createBook(author, "A " + UUID.randomUUID());
        String foreign = books.createBook(newAuthor());

        ApiClient.Response page = author.get("/api/v1/users/me/books?size=1&sort=title");

        assertThat(page.json().at("/totalElements").asLong()).isEqualTo(2);
        assertThat(page.json().at("/totalPages").asInt()).isEqualTo(2);
        assertThat(page.json().at("/content/0/id").asString()).isEqualTo(second);
        ApiClient.Response all = author.get("/api/v1/users/me/books");
        assertThat(all.body()).contains(first).contains(second).doesNotContain(foreign);
        assertThat(author.get("/api/v1/users/me/books?size=51").code()).isEqualTo("INVALID_PAGINATION");
        assertThat(author.get("/api/v1/users/me/books?sort=passwordHash").code()).isEqualTo("INVALID_SORT");
    }

    @Test
    void deletingRequiresTheExactTitleAndAnUnpublishedBook() {
        ApiClient author = newAuthor();
        String title = uniqueTitle();
        String id = books.createBook(author, title);
        String path = BOOKS + "/" + id;

        assertThat(author.delete(path).code()).isEqualTo("TITLE_CONFIRMATION_MISMATCH");
        assertThat(author.delete(path + "?confirmTitle=errado").code()).isEqualTo("TITLE_CONFIRMATION_MISMATCH");
        assertThat(author.get(path + "/details").status()).isEqualTo(200);

        assertThat(author.delete(path + "?confirmTitle=" + URLEncoder.encode(title, StandardCharsets.UTF_8)).status())
                .isEqualTo(204);
        assertThat(author.get(path + "/details").status()).isEqualTo(404);
    }

    @Test
    void publishedBookMustBeUnpublishedBeforeDeletion() {
        ApiClient author = newAuthor();
        String id = books.publishedBook(author);
        String title = author.get(BOOKS + "/" + id + "/details").json().at("/title").asString();
        String confirm = "?confirmTitle=" + URLEncoder.encode(title, StandardCharsets.UTF_8);

        assertThat(author.delete(BOOKS + "/" + id + confirm).code()).isEqualTo("BOOK_PUBLISHED");
        assertThat(author.post(BOOKS + "/" + id + "/unpublish", body()).status()).isEqualTo(200);
        assertThat(author.delete(BOOKS + "/" + id + confirm).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chapters WHERE book_id = ?", Integer.class,
                UUID.fromString(id))).isZero();
    }

    @Test
    void oversizedJsonBodyIsRefused() {
        Map<String, Object> huge = books.newBook(uniqueTitle(), uniqueAcronym());
        huge.put("synopsis", "x".repeat(4 * 1024 * 1024));

        assertThat(newAuthor().post(BOOKS, huge).status()).isEqualTo(400);
    }
}
