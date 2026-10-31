package br.com.readowl.readowl.publishing;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static br.com.readowl.readowl.support.BookFixtures.uniqueAcronym;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class CatalogIntegrationTest {

    private static final String CATALOG = "/api/v1/books";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionOperations transaction;

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

    /**
     * Autor novo por teste: o filtro por autor isola o resultado das obras criadas por outros testes.
     */
    private record Author(ApiClient client, String username, String email) {
    }

    private Author newAuthor() {
        String email = uniqueEmail();
        String username = accounts.registerVerified(email);
        return new Author(accounts.loggedIn(email, PASSWORD), username, email);
    }

    private Map<String, Object> book(String title) {
        return new LinkedHashMap<>(books.newBook(title, uniqueAcronym()));
    }

    private List<String> titles(String queryString) {
        ApiClient.Response response = anonymous.get(CATALOG + "?" + queryString);
        assertThat(response.status()).isEqualTo(200);
        List<String> titles = new ArrayList<>();
        for (JsonNode entry : response.json().at("/content").values()) {
            titles.add(entry.at("/title").asString());
        }
        return titles;
    }

    private static String encoded(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    @Test
    void onlyPublishedBooksAppear() {
        Author author = newAuthor();
        books.publishedBook(author.client(), book("Publicada"));
        books.createBook(author.client(), "Rascunho");
        String unpublished = books.publishedBook(author.client(), book("Retirada"));
        author.client().post("/api/v1/books/" + unpublished + "/unpublish", body());

        assertThat(titles("author=" + author.username())).containsExactly("Publicada");
    }

    @Test
    void bookWithPublishedChapterButUnpublishedBookStaysOut() {
        Author author = newAuthor();
        String book = books.createBook(author.client(), "Capítulo publicado, obra não");
        books.publishChapter(author.client(), books.createChapter(author.client(), book, "Um"), "Texto");

        assertThat(titles("author=" + author.username())).isEmpty();
    }

    @Test
    void suspendedAuthorsBooksDisappear() {
        Author author = newAuthor();
        books.publishedBook(author.client(), book("De autor suspenso"));
        assertThat(titles("author=" + author.username())).hasSize(1);

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", author.email());

        assertThat(titles("author=" + author.username())).isEmpty();
    }

    @Test
    void entryHasPublicDataAndCountsOnlyPublishedChapters() {
        Author author = newAuthor();
        String tag = "Tag" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> data = book("Completa");
        data.put("tags", List.of(tag));
        String id = books.publishedBook(author.client(), data);
        books.createChapter(author.client(), id, "Rascunho secreto");

        ApiClient.Response response = anonymous.get(CATALOG + "?author=" + author.username());
        JsonNode entry = response.json().at("/content/0");

        assertThat(entry.at("/publishedChapters").asInt()).isEqualTo(1);
        assertThat(entry.at("/author/username").asString()).isEqualTo(author.username());
        assertThat(entry.at("/genres/0/name").asString()).isEqualTo("Fantasia");
        assertThat(entry.at("/tags/0/name").asString()).isEqualTo(tag);
        assertThat(entry.at("/lastChapterPublishedAt").asString()).endsWith("Z");
        assertThat(response.body()).doesNotContain(author.email()).doesNotContain("Rascunho secreto").doesNotContain("search_vector");
        assertThat(response.setCookies()).isEmpty();
    }

    @Test
    void searchIgnoresAccentsAndCaseInBothDirections() {
        String word = "Coração" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Author author = newAuthor();
        books.publishedBook(author.client(), book(word));
        String ascii = word.replace("ç", "c").replace("ã", "a");

        assertThat(titles("query=" + encoded(ascii.toUpperCase()))).containsExactly(word);
        assertThat(titles("query=" + encoded(word))).containsExactly(word);
    }

    @Test
    void searchFindsStemsInTitleAndWordsInSynopsis() {
        String unique = "zqx" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Author author = newAuthor();
        Map<String, Object> inSynopsis = book("Sem a palavra");
        inSynopsis.put("synopsis", "Uma história sobre " + unique + " e aventureiros");
        books.publishedBook(author.client(), inSynopsis);

        assertThat(titles("query=" + unique)).containsExactly("Sem a palavra");
        assertThat(titles("query=" + encoded("aventureiro") + "&author=" + author.username())).containsExactly("Sem a palavra");
    }

    @Test
    void searchFindsPartialTitlesAndToleratesTypos() {
        String unique = UUID.randomUUID().toString().replace("-", "").replaceAll("[0-9]", "k").substring(0, 12);
        String title = "Remanescente " + unique;
        Author author = newAuthor();
        books.publishedBook(author.client(), book(title));
        String filter = "&author=" + author.username();

        assertThat(titles("query=" + unique.substring(0, 6) + filter)).containsExactly(title);
        assertThat(titles("query=Remanecente" + filter)).containsExactly(title);
    }

    @Test
    void searchFindsTheAcronym() {
        Author author = newAuthor();
        String acronym = uniqueAcronym();
        books.publishedBook(author.client(), books.newBook("Sigla buscada", acronym));

        assertThat(titles("query=" + acronym.toLowerCase())).containsExactly("Sigla buscada");
    }

    @Test
    void searchDoesNotFindDraftBooks() {
        String unique = "zqx" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Author author = newAuthor();
        books.createBook(author.client(), unique);

        assertThat(titles("query=" + unique)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'; DROP TABLE books; --",
            "%",
            "_",
            "\\",
            "\"aspas abertas",
            "a & b | !c",
            "<-> :*"
    })
    void hostileSearchTextIsTreatedAsText(String query) {
        ApiClient.Response response = anonymous.get(CATALOG + "?query=" + encoded(query));

        assertThat(response.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'books'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void percentSignDoesNotMatchEverything() {
        Author author = newAuthor();
        books.publishedBook(author.client(), book("Um título qualquer"));

        assertThat(titles("query=" + encoded("%") + "&author=" + author.username())).isEmpty();
    }

    @Test
    void filtersCombineAndNarrowTheResult() {
        Author author = newAuthor();
        String tag = "Tag" + UUID.randomUUID().toString().substring(0, 8);
        UUID fantasy = books.genreId("Fantasia");
        UUID drama = books.genreId("Drama");
        Map<String, Object> both = book("Dois gêneros");
        both.put("genreIds", List.of(fantasy, drama));
        both.put("tags", List.of(tag));
        books.publishedBook(author.client(), both);
        String completed = books.publishedBook(author.client(), book("Um gênero"));
        author.client().patch(CATALOG + "/" + completed, body("status", "COMPLETED"));
        String filter = "author=" + author.username();

        assertThat(titles(filter + "&genre=" + fantasy)).containsExactlyInAnyOrder("Dois gêneros", "Um gênero");
        assertThat(titles(filter + "&genre=" + fantasy + "&genre=" + drama)).containsExactly("Dois gêneros");
        assertThat(titles(filter + "&tag=" + tag.toUpperCase())).containsExactly("Dois gêneros");
        assertThat(titles(filter + "&status=COMPLETED")).containsExactly("Um gênero");
        assertThat(titles(filter + "&status=COMPLETED&tag=" + tag)).isEmpty();
    }

    @Test
    void sortingAndPaginationWork() {
        Author author = newAuthor();
        books.publishedBook(author.client(), book("B"));
        books.publishedBook(author.client(), book("A"));
        String filter = "author=" + author.username();

        assertThat(titles(filter + "&sort=title")).containsExactly("A", "B");
        assertThat(titles(filter + "&sort=-title")).containsExactly("B", "A");
        assertThat(titles(filter)).containsExactly("A", "B");
        assertThat(titles(filter + "&sort=createdAt")).containsExactly("B", "A");

        ApiClient.Response page = anonymous.get(CATALOG + "?" + filter + "&sort=title&size=1&page=1");
        assertThat(page.json().at("/content/0/title").asString()).isEqualTo("B");
        assertThat(page.json().at("/totalElements").asLong()).isEqualTo(2);
        assertThat(page.json().at("/totalPages").asInt()).isEqualTo(2);
        assertThat(page.json().at("/page").asInt()).isEqualTo(1);
    }

    @Test
    void newChapterMovesTheBookToTheTopButRepublishingWithoutChangesDoesNot() {
        Author author = newAuthor();
        String older = books.publishedBook(author.client(), book("Mais antiga"));
        books.publishedBook(author.client(), book("Mais recente"));
        String filter = "author=" + author.username();
        assertThat(titles(filter)).containsExactly("Mais recente", "Mais antiga");

        String chapter = author.client().get(CATALOG + "/" + older + "/contents").json().at("/chapters/0/id").asString();
        long version = author.client().get("/api/v1/chapters/" + chapter + "/draft").json().at("/version").asLong();
        author.client().post("/api/v1/chapters/" + chapter + "/publish", body("draftVersion", version));
        assertThat(titles(filter)).containsExactly("Mais recente", "Mais antiga");

        books.publishChapter(author.client(), books.createChapter(author.client(), older, "Novo"), "Novidade");
        assertThat(titles(filter)).containsExactly("Mais antiga", "Mais recente");
    }

    @Test
    void invalidParametersAreRefused() {
        assertThat(anonymous.get(CATALOG + "?sort=passwordHash").code()).isEqualTo("INVALID_SORT");
        assertThat(anonymous.get(CATALOG + "?sort=" + encoded("b.title; DROP TABLE books")).code()).isEqualTo("INVALID_SORT");
        assertThat(anonymous.get(CATALOG + "?sort=relevance").code()).isEqualTo("INVALID_SORT");
        assertThat(anonymous.get(CATALOG + "?size=51").code()).isEqualTo("INVALID_PAGINATION");
        assertThat(anonymous.get(CATALOG + "?page=-1").code()).isEqualTo("INVALID_PAGINATION");
        assertThat(anonymous.get(CATALOG + "?query=" + "a".repeat(101)).code()).isEqualTo("QUERY_TOO_LONG");
        assertThat(anonymous.get(CATALOG + "?status=INVENTADO").status()).isEqualTo(400);
        assertThat(anonymous.get(CATALOG + "?genre=nao-e-uuid").status()).isEqualTo(400);
        assertThat(anonymous.get(CATALOG + "?query=x&sort=relevance").status()).isEqualTo(200);
    }

    @Test
    void searchQueryUsesTheIndexes() {
        // Com poucas linhas, o planejador prefere varrer a tabela: aqui importa que os índices sirvam à consulta.
        List<String> plan = transaction.execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return jdbc.queryForList("""
                    EXPLAIN SELECT id FROM books
                    WHERE search_vector @@ websearch_to_tsquery('portuguese', immutable_unaccent('aura'))
                       OR immutable_unaccent(lower(title)) LIKE '%aura%'
                       OR acronym = 'AURA'
                    """, String.class);
        });

        assertThat(String.join("\n", plan))
                .contains("ix_books_search").contains("ix_books_title_trgm").contains("ix_books_acronym");
    }
}
