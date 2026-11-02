package br.com.readowl.readowl.community;

import br.com.readowl.readowl.privacy.application.RetentionTasks;
import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.FakeRemoteImages;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class CommentImagesIntegrationTest {

    private static final String IMAGE = "https://i.imgur.com/comentario.jpg";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private FakeRemoteImages remote;

    @Autowired
    private RetentionTasks retention;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private ApiClient reader;
    private ApiClient visitor;
    private String book;
    private String comments;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        mail.setFailing(false);
        remote.reset();
        BookFixtures books = new BookFixtures(jdbc);
        String authorEmail = uniqueEmail();
        accounts.registerVerified(authorEmail);
        book = books.publishedBook(accounts.loggedIn(authorEmail, PASSWORD));
        String readerEmail = uniqueEmail();
        accounts.registerVerified(readerEmail);
        reader = accounts.loggedIn(readerEmail, PASSWORD);
        visitor = accounts.client();
        comments = "/api/v1/books/" + book + "/comments";
    }

    private ApiClient.Response comment(String text, String image) {
        return reader.post(comments, body("content", text, "imageUrl", image));
    }

    private String commentWithImage() {
        ApiClient.Response created = comment("Veja esta arte", IMAGE);
        assertThat(created.status()).isEqualTo(201);
        return created.json().at("/id").asString();
    }

    @Test
    void aCommentCanHaveAnImageThatIsNeverDownloaded() {
        ApiClient.Response created = comment("Veja esta arte", " " + IMAGE + " ");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().at("/imageUrl").asString()).isEqualTo(IMAGE);
        assertThat(visitor.get(comments).json().at("/content/0/imageUrl").asString()).isEqualTo(IMAGE);
        assertThat(jdbc.queryForObject("SELECT image_url FROM comments WHERE book_id = ?::uuid", String.class, book)).isEqualTo(IMAGE);
        assertThat(remote.downloads()).isEmpty();
    }

    @Test
    void theImageIsOptional() {
        assertThat(reader.post(comments, body("content", "Só texto")).json().at("/imageUrl").isNull()).isTrue();
        assertThat(comment("Em branco", "  ").json().at("/imageUrl").isNull()).isTrue();
    }

    @Test
    void theTextIsStillRequired() {
        assertThat(comment(" ", IMAGE).code()).isEqualTo("VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://site-desconhecido.example.com/a.jpg", "http://i.imgur.com/a.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA"})
    void anImageOutsideTheAllowedSitesIsRefused(String url) {
        ApiClient.Response refused = comment("Oi", url);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("INVALID_COMMENT_IMAGE");
        assertThat(visitor.get(comments).json().at("/totalElements").asLong()).isZero();
    }

    @Test
    void aReplyCanHaveAnImage() {
        String parent = commentWithImage();

        assertThat(reader.post("/api/v1/comments/" + parent + "/replies", body("content", "Resposta", "imageUrl", IMAGE))
                .json().at("/imageUrl").asString()).isEqualTo(IMAGE);
    }

    @Test
    void editingKeepsChangesOrRemovesTheImage() {
        String path = "/api/v1/comments/" + commentWithImage();
        String other = "https://i.ibb.co/outra.jpg";

        assertThat(reader.patch(path, body("content", "Novo texto")).json().at("/imageUrl").asString()).isEqualTo(IMAGE);

        ApiClient.Response changed = reader.patch(path, body("content", "Novo texto", "imageUrl", other));
        assertThat(changed.json().at("/imageUrl").asString()).isEqualTo(other);
        assertThat(changed.json().at("/edited").asBoolean()).isTrue();

        assertThat(reader.patch(path, body("content", "Novo texto", "imageUrl", "")).json().at("/imageUrl").isNull()).isTrue();
        assertThat(reader.patch(path, body("content", "Novo texto", "imageUrl", "https://fora.example.com/a.jpg")).code()).isEqualTo("INVALID_COMMENT_IMAGE");
    }

    @Test
    void onlyChangingTheImageCountsAsAnEdit() {
        String path = "/api/v1/comments/" + reader.post(comments, body("content", "Oi")).json().at("/id").asString();

        assertThat(reader.patch(path, body("content", "Oi", "imageUrl", IMAGE)).json().at("/edited").asBoolean()).isTrue();
    }

    @Test
    void aRemovedCommentDoesNotShowTheImage() {
        String parent = commentWithImage();
        reader.post("/api/v1/comments/" + parent + "/replies", body("content", "Mantém o principal na lista"));

        assertThat(reader.delete("/api/v1/comments/" + parent).status()).isEqualTo(204);

        var listed = visitor.get(comments).json().at("/content/0");
        assertThat(listed.at("/removed").asBoolean()).isTrue();
        assertThat(listed.at("/imageUrl").isNull()).isTrue();
    }

    @Test
    void theRetentionOfDeletedCommentsAlsoClearsTheImage() {
        String expired = commentWithImage();
        String recent = reader.post(comments, body("content", "Excluído há pouco", "imageUrl", IMAGE)).json().at("/id").asString();
        assertThat(reader.delete("/api/v1/comments/" + expired).status()).isEqualTo(204);
        assertThat(reader.delete("/api/v1/comments/" + recent).status()).isEqualTo(204);
        jdbc.update("UPDATE comments SET deleted_at = now() - interval '31 days' WHERE id = ?::uuid", expired);

        retention.run();

        assertThat(imageOf(expired)).isNull();
        assertThat(imageOf(recent)).isEqualTo(IMAGE);
    }

    private String imageOf(String comment) {
        return jdbc.queryForObject("SELECT image_url FROM comments WHERE id = ?::uuid", String.class, comment);
    }

    @Test
    void theReportKeepsTheImageAddress() {
        String id = commentWithImage();
        String reporterEmail = uniqueEmail();
        accounts.registerVerified(reporterEmail);

        assertThat(accounts.loggedIn(reporterEmail, PASSWORD).post("/api/v1/reports",
                body("targetType", "COMMENT", "targetId", id, "reason", "ABUSE")).status()).isEqualTo(201);

        String snapshot = jdbc.queryForObject("SELECT target_snapshot FROM reports WHERE target_id = ?", String.class, UUID.fromString(id));
        assertThat(snapshot).contains(IMAGE);
    }

    @Test
    void theExportIncludesTheImage() {
        commentWithImage();

        assertThat(reader.get("/api/v1/users/me/export").body()).contains(IMAGE);
    }
}
