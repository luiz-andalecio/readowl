package br.com.readowl.readowl.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cria obras e capítulos pela API.
 */
public final class BookFixtures {

    private final JdbcTemplate jdbc;

    public BookFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID genreId(String name) {
        return jdbc.queryForObject("SELECT id FROM genres WHERE name = ?", UUID.class, name);
    }

    public Map<String, Object> newBook(String title, String acronym) {
        return body("title", title, "acronym", acronym, "synopsis", "Uma sinopse.",
                "genreIds", List.of(genreId("Fantasia")), "tags", List.of());
    }

    /**
     * Sigla sempre única, para os testes não colidirem entre si.
     */
    public static String uniqueAcronym() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    public String createBook(ApiClient author) {
        return createBook(author, "Obra de Teste");
    }

    public String createBook(ApiClient author, String title) {
        ApiClient.Response response = author.post("/api/v1/books", newBook(title, uniqueAcronym()));
        assertThat(response.status()).isEqualTo(201);
        return response.json().at("/id").asString();
    }

    public String createChapter(ApiClient author, String bookId, String title) {
        ApiClient.Response response = author.post("/api/v1/books/" + bookId + "/chapters", body("title", title));
        assertThat(response.status()).isEqualTo(201);
        return response.json().at("/id").asString();
    }

    public static Map<String, Object> document(String text) {
        return body("type", "doc", "content", List.of(body("type", "paragraph", "content",
                List.of(body("type", "text", "text", text)))));
    }

    /**
     * Grava o texto no rascunho e devolve a versão resultante.
     */
    public long writeDraft(ApiClient author, String chapterId, String text) {
        String path = "/api/v1/chapters/" + chapterId + "/draft";
        long version = author.get(path).json().at("/version").asLong();
        ApiClient.Response saved = author.put(path, body("version", version, "document", document(text)));
        assertThat(saved.status()).isEqualTo(200);
        return saved.json().at("/version").asLong();
    }

    public void publishChapter(ApiClient author, String chapterId, String text) {
        long version = writeDraft(author, chapterId, text);
        assertThat(author.post("/api/v1/chapters/" + chapterId + "/publish", body("draftVersion", version)).status())
                .isEqualTo(200);
    }

    /**
     * Obra pública com os dados informados, para testes de catálogo e leitura.
     */
    public String publishedBook(ApiClient author, Map<String, Object> data) {
        ApiClient.Response created = author.post("/api/v1/books", data);
        assertThat(created.status()).isEqualTo(201);
        String bookId = created.json().at("/id").asString();
        publishChapter(author, createChapter(author, bookId, "Capítulo 1"), "Era uma vez.");
        assertThat(author.post("/api/v1/books/" + bookId + "/publish", body()).status()).isEqualTo(200);
        return bookId;
    }

    public String publishedBook(ApiClient author) {
        String bookId = createBook(author);
        publishChapter(author, createChapter(author, bookId, "Capítulo 1"), "Era uma vez.");
        assertThat(author.post("/api/v1/books/" + bookId + "/publish", body()).status()).isEqualTo(200);
        return bookId;
    }
}
