package br.com.readowl.readowl.notification;

import br.com.readowl.readowl.notification.application.NotificationWorker;
import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class NotificationIntegrationTest {

    private static final String NOTIFICATIONS = "/api/v1/notifications";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationWorker worker;

    private AccountFixtures accounts;
    private BookFixtures books;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
        worker.run();
    }

    @AfterEach
    void removeTestTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_notifications ON notifications");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_notifications()");
    }

    private ApiClient newUser() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private ApiClient follower(String book) {
        ApiClient reader = newUser();
        assertThat(reader.put("/api/v1/library/" + book, body()).status()).isEqualTo(200);
        return reader;
    }

    private String publishNewChapter(ApiClient author, String book, String title) {
        String chapter = books.createChapter(author, book, title);
        books.publishChapter(author, chapter, "Texto");
        return chapter;
    }

    private long unread(ApiClient user) {
        return user.get(NOTIFICATIONS + "/unread-count").json().at("/unread").asLong();
    }

    @Test
    void notificationsRequireLoginAndCsrf() {
        ApiClient anonymous = accounts.client();

        assertThat(anonymous.get(NOTIFICATIONS).status()).isEqualTo(401);
        assertThat(anonymous.get(NOTIFICATIONS + "/unread-count").status()).isEqualTo(401);
        assertThat(newUser().postWithoutCsrf(NOTIFICATIONS + "/read-all", body()).status()).isEqualTo(403);
    }

    @Test
    void followersAreNotifiedOfNewChaptersByTheWorkerNotDuringPublication() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = follower(book);
        ApiClient stranger = newUser();

        String chapter = publishNewChapter(author, book, "Novo capítulo");
        assertThat(unread(reader)).isZero();

        worker.processEvents();

        assertThat(unread(reader)).isEqualTo(1);
        assertThat(unread(stranger)).isZero();
        assertThat(unread(author)).isZero();
        ApiClient.Response list = reader.get(NOTIFICATIONS);
        assertThat(list.json().at("/totalElements").asLong()).isEqualTo(1);
        assertThat(list.json().at("/content/0/type").asString()).isEqualTo("NEW_CHAPTER");
        assertThat(list.json().at("/content/0/read").asBoolean()).isFalse();
        assertThat(list.json().at("/content/0/book/id").asString()).isEqualTo(book);
        assertThat(list.json().at("/content/0/chapter/id").asString()).isEqualTo(chapter);
        assertThat(list.json().at("/content/0/chapter/slug").asString()).isEqualTo("novo-capitulo");
    }

    @Test
    void onlyTheFirstPublicationOfAChapterInAPublicBookNotifies() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = follower(book);
        String chapter = publishNewChapter(author, book, "Primeira vez");
        worker.processEvents();
        assertThat(unread(reader)).isEqualTo(1);

        // Corrigir e republicar não é novidade.
        books.publishChapter(author, chapter, "Texto corrigido");
        author.post("/api/v1/chapters/" + chapter + "/unpublish", body());
        long version = author.get("/api/v1/chapters/" + chapter + "/draft").json().at("/version").asLong();
        author.post("/api/v1/chapters/" + chapter + "/publish", body("draftVersion", version));
        worker.processEvents();
        assertThat(unread(reader)).isEqualTo(1);

        // Capítulo publicado com a obra fora do ar também não.
        author.post("/api/v1/books/" + book + "/unpublish", body());
        publishNewChapter(author, book, "Durante a pausa");
        author.post("/api/v1/books/" + book + "/publish", body());
        worker.processEvents();
        assertThat(unread(reader)).isEqualTo(1);
    }

    @Test
    void reprocessingAnEventDoesNotDuplicateNotifications() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = follower(book);
        String chapter = publishNewChapter(author, book, "Único");

        worker.processEvents();
        jdbc.update("UPDATE outbox_events SET processed_at = NULL WHERE chapter_id = ?", UUID.fromString(chapter));
        worker.processEvents();

        assertThat(unread(reader)).isEqualTo(1);
    }

    @Test
    void simultaneousWorkersProcessEachEventOnce() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        List<ApiClient> readers = IntStream.range(0, 3).mapToObj(i -> follower(book)).toList();
        publishNewChapter(author, book, "Um");
        publishNewChapter(author, book, "Dois");

        List<CompletableFuture<Void>> workers = IntStream.range(0, 4)
                .mapToObj(i -> CompletableFuture.runAsync(worker::processEvents))
                .toList();
        workers.forEach(CompletableFuture::join);

        assertThat(readers).allSatisfy(reader -> assertThat(unread(reader)).isEqualTo(2));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE book_id = ? AND processed_at IS NOT NULL
                """, Integer.class, UUID.fromString(book))).isEqualTo(2);
    }

    @Test
    void failuresAreRetriedALimitedNumberOfTimesAndRecorded() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = follower(book);
        String chapter = publishNewChapter(author, book, "Falha");
        UUID chapterId = UUID.fromString(chapter);
        jdbc.execute("""
                CREATE FUNCTION test_fail_notifications() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'falha simulada'; END
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER test_fail_notifications BEFORE INSERT ON notifications
                FOR EACH ROW EXECUTE FUNCTION test_fail_notifications()
                """);

        worker.processEvents();
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE chapter_id = ?", Integer.class, chapterId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() FROM outbox_events WHERE chapter_id = ?", Boolean.class, chapterId)).isTrue();

        // Respeita a espera até a próxima tentativa.
        worker.processEvents();
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE chapter_id = ?", Integer.class, chapterId)).isEqualTo(1);

        for (int attempt = 0; attempt < 2; attempt++) {
            jdbc.update("UPDATE outbox_events SET next_attempt_at = now() WHERE chapter_id = ?", chapterId);
            worker.processEvents();
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE chapter_id = ?", Integer.class, chapterId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT failed_at IS NOT NULL FROM outbox_events WHERE chapter_id = ?", Boolean.class, chapterId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE chapter_id = ?", String.class, chapterId)).isNotBlank();

        removeTestTriggers();
        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() WHERE chapter_id = ?", chapterId);
        worker.processEvents();
        assertThat(unread(reader)).isZero();
    }

    @Test
    void publishingDoesNotDependOnTheWorker() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        follower(book);
        jdbc.execute("""
                CREATE FUNCTION test_fail_notifications() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'falha simulada'; END
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER test_fail_notifications BEFORE INSERT ON notifications
                FOR EACH ROW EXECUTE FUNCTION test_fail_notifications()
                """);

        String chapter = publishNewChapter(author, book, "Publicado mesmo assim");

        assertThat(jdbc.queryForObject("SELECT state FROM chapters WHERE id = ?", String.class, UUID.fromString(chapter)))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void anUndoneChapterPublicationDoesNotLeaveAnEvent() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = books.createChapter(author, book, "Conflito");
        long version = books.writeDraft(author, chapter, "Texto");

        assertThat(author.post("/api/v1/chapters/" + chapter + "/publish", body("draftVersion", version - 1)).status()).isEqualTo(409);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE chapter_id = ?", Integer.class,
                UUID.fromString(chapter))).isZero();
    }

    @Test
    void readStateBelongsToEachUser() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient first = follower(book);
        ApiClient second = follower(book);
        publishNewChapter(author, book, "A");
        publishNewChapter(author, book, "B");
        worker.processEvents();
        String id = first.get(NOTIFICATIONS).json().at("/content/0/id").asString();

        assertThat(second.patch(NOTIFICATIONS + "/" + id, body("read", true)).code()).isEqualTo("NOTIFICATION_NOT_FOUND");
        assertThat(unread(first)).isEqualTo(2);

        assertThat(first.patch(NOTIFICATIONS + "/" + id, body("read", true)).status()).isEqualTo(204);
        assertThat(unread(first)).isEqualTo(1);
        assertThat(first.get(NOTIFICATIONS + "?unreadOnly=true").json().at("/totalElements").asLong()).isEqualTo(1);
        assertThat(first.get(NOTIFICATIONS).json().at("/totalElements").asLong()).isEqualTo(2);

        assertThat(first.patch(NOTIFICATIONS + "/" + id, body("read", false)).status()).isEqualTo(204);
        assertThat(unread(first)).isEqualTo(2);
        assertThat(first.patch(NOTIFICATIONS + "/" + id, body()).code()).isEqualTo("VALIDATION_FAILED");

        assertThat(first.post(NOTIFICATIONS + "/read-all", body()).status()).isEqualTo(204);
        assertThat(unread(first)).isZero();
        assertThat(unread(second)).isEqualTo(2);
    }

    @Test
    void contentThatLeftTheAirDisappearsFromTheListAndCount() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = follower(book);
        String chapter = publishNewChapter(author, book, "Volátil");
        worker.processEvents();

        author.post("/api/v1/chapters/" + chapter + "/unpublish", body());
        assertThat(unread(reader)).isZero();
        assertThat(reader.get(NOTIFICATIONS).json().at("/totalElements").asLong()).isZero();

        long version = author.get("/api/v1/chapters/" + chapter + "/draft").json().at("/version").asLong();
        author.post("/api/v1/chapters/" + chapter + "/publish", body("draftVersion", version));
        assertThat(unread(reader)).isEqualTo(1);

        author.post("/api/v1/chapters/" + chapter + "/unpublish", body());
        assertThat(author.delete("/api/v1/chapters/" + chapter).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE chapter_id = ?", Integer.class,
                UUID.fromString(chapter))).isZero();
    }

    @Test
    void chapterThatLeavesTheAirBeforeTheWorkerRunsNotifiesNobody() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = follower(book);
        String chapter = publishNewChapter(author, book, "Arrependido");
        author.post("/api/v1/chapters/" + chapter + "/unpublish", body());

        worker.processEvents();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE chapter_id = ?", Integer.class,
                UUID.fromString(chapter))).isZero();
        assertThat(unread(reader)).isZero();
    }

    @Test
    void emailsAreOptInAndSentOnlyToThoseWhoAskedForThem() throws InterruptedException {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String optedInEmail = uniqueEmail();
        accounts.registerVerified(optedInEmail);
        ApiClient optedIn = accounts.loggedIn(optedInEmail, PASSWORD);
        optedIn.put("/api/v1/library/" + book, body());
        String defaultEmail = uniqueEmail();
        accounts.registerVerified(defaultEmail);
        accounts.loggedIn(defaultEmail, PASSWORD).put("/api/v1/library/" + book, body());
        assertThat(optedIn.get("/api/v1/users/me").json().at("/emailNewChapters").asBoolean()).isFalse();
        assertThat(optedIn.patch("/api/v1/users/me", body("emailNewChapters", true)).json().at("/emailNewChapters").asBoolean()).isTrue();
        // Os demais campos do perfil continuam iguais.
        assertThat(optedIn.get("/api/v1/users/me").json().at("/name").asString()).isEqualTo("Pessoa Leitora");

        publishNewChapter(author, book, "Com aviso");
        worker.run();

        SimpleMailMessage message = mail.awaitMessage(optedInEmail);
        assertThat(message.getSubject()).isEqualTo("Novo capítulo em uma obra que você segue");
        assertThat(message.getText()).contains("Capítulo: Com aviso").contains("/capitulos/com-aviso").contains("/conta");
        assertThat(mail.receivesNothing(defaultEmail)).isTrue();

        worker.run();
        assertThat(mail.receivesNothing(optedInEmail)).isTrue();
    }

    @Test
    void emailFailuresAreRetriedALimitedNumberOfTimes() throws InterruptedException {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient reader = accounts.loggedIn(email, PASSWORD);
        reader.put("/api/v1/library/" + book, body());
        reader.patch("/api/v1/users/me", body("emailNewChapters", true));
        String chapter = publishNewChapter(author, book, "SMTP fora");
        UUID chapterId = UUID.fromString(chapter);

        mail.setFailing(true);
        worker.run();
        assertThat(unread(reader)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT email_status FROM notifications WHERE chapter_id = ?", String.class, chapterId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT email_attempts FROM notifications WHERE chapter_id = ?", Integer.class, chapterId)).isEqualTo(1);

        // Respeita a espera até a próxima tentativa.
        worker.deliverEmails();
        assertThat(jdbc.queryForObject("SELECT email_attempts FROM notifications WHERE chapter_id = ?", Integer.class, chapterId)).isEqualTo(1);

        mail.setFailing(false);
        jdbc.update("UPDATE notifications SET email_next_attempt_at = now() WHERE chapter_id = ?", chapterId);
        worker.deliverEmails();
        assertThat(jdbc.queryForObject("SELECT email_status FROM notifications WHERE chapter_id = ?", String.class, chapterId)).isEqualTo("SENT");
        mail.awaitMessage(email);

        String other = publishNewChapter(author, book, "SMTP fora de novo");
        UUID otherId = UUID.fromString(other);
        worker.processEvents();
        mail.setFailing(true);
        for (int attempt = 0; attempt < 3; attempt++) {
            jdbc.update("UPDATE notifications SET email_next_attempt_at = now() WHERE chapter_id = ?", otherId);
            worker.deliverEmails();
        }
        assertThat(jdbc.queryForObject("SELECT email_status FROM notifications WHERE chapter_id = ?", String.class, otherId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT email_last_error FROM notifications WHERE chapter_id = ?", String.class, otherId)).isNotBlank();

        mail.setFailing(false);
        jdbc.update("UPDATE notifications SET email_next_attempt_at = now() WHERE chapter_id = ?", otherId);
        worker.deliverEmails();
        assertThat(mail.receivesNothing(email)).isTrue();
    }

    @Test
    void emailIsSkippedIfTheUserGaveUpOrTheChapterLeftBeforeSending() throws InterruptedException {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient reader = accounts.loggedIn(email, PASSWORD);
        reader.put("/api/v1/library/" + book, body());
        reader.patch("/api/v1/users/me", body("emailNewChapters", true));
        String chapter = publishNewChapter(author, book, "Desistiu");
        worker.processEvents();

        reader.patch("/api/v1/users/me", body("emailNewChapters", false));
        worker.deliverEmails();

        assertThat(jdbc.queryForObject("SELECT email_status FROM notifications WHERE chapter_id = ?", String.class,
                UUID.fromString(chapter))).isEqualTo("SKIPPED");
        assertThat(mail.receivesNothing(email)).isTrue();
    }

    @Test
    void suspendedUsersAreNotNotified() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        accounts.loggedIn(email, PASSWORD).put("/api/v1/library/" + book, body());
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        String chapter = publishNewChapter(author, book, "Sem aviso");
        worker.processEvents();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE chapter_id = ?", Integer.class,
                UUID.fromString(chapter))).isZero();
    }

    @Test
    void oldProcessedEventsArePurgedButFailuresAreKept() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String processed = publishNewChapter(author, book, "Antigo");
        String failed = publishNewChapter(author, book, "Falhou");
        jdbc.update("UPDATE outbox_events SET processed_at = now() - interval '31 days' WHERE chapter_id = ?", UUID.fromString(processed));
        jdbc.update("UPDATE outbox_events SET failed_at = now() - interval '31 days' WHERE chapter_id = ?", UUID.fromString(failed));

        worker.purgeProcessedEvents();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE chapter_id = ?", Integer.class, UUID.fromString(processed))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE chapter_id = ?", Integer.class, UUID.fromString(failed))).isEqualTo(1);
    }
}
