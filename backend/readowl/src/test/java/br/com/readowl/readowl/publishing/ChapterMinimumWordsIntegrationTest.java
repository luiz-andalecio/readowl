package br.com.readowl.readowl.publishing;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiTestConfiguration;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import br.com.readowl.readowl.support.TestDatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os demais testes publicam capítulos curtos, com o limite reduzido em application-test.properties. Aqui vale o limite real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "readowl.limits.chapter-min-words=750")
@ActiveProfiles("test")
@Import({TestDatabaseConfiguration.class, ApiTestConfiguration.class})
class ChapterMinimumWordsIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient author;
    private BookFixtures books;
    private String chapter;

    @BeforeEach
    void setUp() {
        AccountFixtures accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        mail.setFailing(false);
        books = new BookFixtures(jdbc);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        author = accounts.loggedIn(email, PASSWORD);
        chapter = books.createChapter(author, books.createBook(author), "Capítulo 1");
    }

    private ApiClient.Response publish(String text) {
        long version = books.writeDraft(author, chapter, text);
        return author.post("/api/v1/chapters/" + chapter + "/publish", body("draftVersion", version));
    }

    private static String words(int count) {
        return "palavra ".repeat(count);
    }

    @Test
    void theProductionLimitIs750Words() throws IOException {
        assertThat(PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))
                .getProperty("readowl.limits.chapter-min-words")).isEqualTo("750");
    }

    @Test
    void aChapterWith750WordsIsPublished() {
        assertThat(publish(words(750)).status()).isEqualTo(200);
    }

    @Test
    void aChapterWithOneWordLessIsRefusedAndTheAnswerSaysHowManyAreMissing() {
        ApiClient.Response refused = publish(words(749));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("CHAPTER_TOO_SHORT");
        assertThat(refused.json().at("/minWords").asInt()).isEqualTo(750);
        assertThat(refused.json().at("/words").asInt()).isEqualTo(749);
    }

    @Test
    void aShortDraftCanStillBeSaved() {
        assertThat(books.writeDraft(author, chapter, "Comecei agora.")).isPositive();
    }

    @Test
    void marksAndPunctuationDoNotCountAsWords() {
        assertThat(publish(words(749) + " — ... !").code()).isEqualTo("CHAPTER_TOO_SHORT");
    }

    @Test
    void imagesDoNotReplaceTheWords() {
        Map<String, Object> text = body("type", "paragraph", "content", List.of(body("type", "text", "text", words(749))));
        Map<String, Object> image = body("type", "image", "attrs", body("src", "https://i.imgur.com/a.jpg"));
        String path = "/api/v1/chapters/" + chapter + "/draft";
        long version = author.get(path).json().at("/version").asLong();
        ApiClient.Response saved = author.put(path, body("version", version, "document", body("type", "doc", "content", List.of(text, image))));
        assertThat(saved.status()).isEqualTo(200);

        assertThat(author.post("/api/v1/chapters/" + chapter + "/publish", body("draftVersion", saved.json().at("/version").asLong())).code())
                .isEqualTo("CHAPTER_TOO_SHORT");
    }
}
