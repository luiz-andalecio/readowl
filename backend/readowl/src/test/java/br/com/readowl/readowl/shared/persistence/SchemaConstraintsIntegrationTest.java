package br.com.readowl.readowl.shared.persistence;

import br.com.readowl.readowl.support.IntegrationTest;
import br.com.readowl.readowl.support.SqlFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class SchemaConstraintsIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    private SqlFixtures db;

    @BeforeEach
    void setUp() {
        db = new SqlFixtures(jdbc);
    }

    @Test
    void rejectsDuplicateEmailAndNonNormalizedEmail() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        db.user("a" + unique, unique + "@example.com");

        assertViolation(() -> db.user("b" + unique, unique + "@example.com"));
        assertViolation(() -> db.user("c" + unique, unique.toUpperCase() + "X@EXAMPLE.COM"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "Maiuscula", "com espaco", "-inicio"})
    void rejectsInvalidUsername(String username) {
        assertViolation(() -> db.user(username, UUID.randomUUID() + "@example.com"));
    }

    @Test
    void rejectsChapterInVolumeOfAnotherBook() {
        UUID author = db.user();
        UUID book = db.book(author);
        UUID volumeOfOtherBook = db.volume(db.book(author), 1);

        assertViolation(() -> db.chapter(book, volumeOfOtherBook, 1));
    }

    @Test
    void rejectsRepeatedChapterOrderInsideVolumeAndOutsideVolumes() {
        UUID book = db.book(db.user());
        UUID volume = db.volume(book, 1);
        db.chapter(book, volume, 1);
        db.chapter(book, null, 1);

        assertViolation(() -> db.chapter(book, volume, 1));
        assertViolation(() -> db.chapter(book, null, 1));
    }

    @Test
    void allowsSwappingOrderInsideTransaction() {
        UUID book = db.book(db.user());
        UUID first = db.chapter(book, null, 1);
        UUID second = db.chapter(book, null, 2);

        assertThatCode(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE chapters SET display_order = 2 WHERE id = ?", first);
            jdbc.update("UPDATE chapters SET display_order = 1 WHERE id = ?", second);
        })).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6})
    void rejectsRatingOutsideRange(int score) {
        UUID user = db.user();
        UUID book = db.book(user);

        assertViolation(() -> db.rating(user, book, score));
    }

    @Test
    void rejectsSecondRatingFromSameUser() {
        UUID user = db.user();
        UUID book = db.book(user);
        db.rating(user, book, 5);

        assertViolation(() -> db.rating(user, book, 1));
    }

    @Test
    void rejectsCommentOnChapterOfAnotherBook() {
        UUID user = db.user();
        UUID book = db.book(user);
        UUID chapterOfOtherBook = db.chapter(db.book(user), null, 1);

        assertViolation(() -> db.comment(user, book, chapterOfOtherBook, null));
    }

    @Test
    void rejectsReplyToCommentOfAnotherBook() {
        UUID user = db.user();
        UUID book = db.book(user);
        UUID commentOfOtherBook = db.comment(user, db.book(user), null, null);

        assertViolation(() -> db.comment(user, book, null, commentOfOtherBook));
    }

    @Test
    void rejectsPublishingRevisionOfAnotherChapter() {
        UUID author = db.user();
        UUID book = db.book(author);
        UUID chapter = db.chapter(book, null, 1);
        UUID revisionOfOtherChapter = db.revision(db.chapter(book, null, 2), author);

        assertViolation(() -> db.publish(chapter, revisionOfOtherChapter));
    }

    @Test
    void rejectsPublishedChapterWithoutRevision() {
        UUID chapter = db.chapter(db.book(db.user()), null, 1);

        assertViolation(() -> jdbc.update("UPDATE chapters SET state = 'PUBLISHED' WHERE id = ?", chapter));
    }

    @Test
    void rejectsDeletingVolumeThatStillHasChapters() {
        UUID book = db.book(db.user());
        UUID volume = db.volume(book, 1);
        db.chapter(book, volume, 1);

        assertViolation(() -> jdbc.update("DELETE FROM volumes WHERE id = ?", volume));
    }

    @Test
    void rejectsDeletingAuthorThatStillHasBooks() {
        UUID author = db.user();
        db.book(author);

        assertViolation(() -> jdbc.update("DELETE FROM users WHERE id = ?", author));
    }

    @Test
    void deletingBookRemovesItsWholeContent() {
        UUID author = db.user();
        UUID reader = db.user();
        UUID book = db.book(author);
        UUID volume = db.volume(book, 1);
        UUID chapter = db.chapter(book, volume, 1);
        db.publish(chapter, db.revision(chapter, author));
        db.draft(chapter);
        UUID comment = db.comment(reader, book, chapter, null);
        db.comment(reader, book, chapter, comment);
        db.rating(reader, book, 4);
        db.follow(reader, book);

        jdbc.update("DELETE FROM books WHERE id = ?", book);

        assertThat(count("volumes", "book_id", book)).isZero();
        assertThat(count("chapters", "book_id", book)).isZero();
        assertThat(count("chapter_revisions", "chapter_id", chapter)).isZero();
        assertThat(count("chapter_drafts", "chapter_id", chapter)).isZero();
        assertThat(count("comments", "book_id", book)).isZero();
        assertThat(count("book_ratings", "book_id", book)).isZero();
        assertThat(count("book_follows", "book_id", book)).isZero();
        assertThat(count("users", "id", reader)).isOne();
    }

    @Test
    void rejectsDeletingRevisionThatIsPublished() {
        UUID author = db.user();
        UUID chapter = db.chapter(db.book(author), null, 1);
        UUID revision = db.revision(chapter, author);
        db.publish(chapter, revision);

        assertViolation(() -> jdbc.update("DELETE FROM chapter_revisions WHERE id = ?", revision));
    }

    private void assertViolation(Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class);
    }

    private int count(String table, String column, UUID value) {
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return total == null ? 0 : total;
    }
}
