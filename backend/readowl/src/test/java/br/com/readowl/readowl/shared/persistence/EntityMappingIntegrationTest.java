package br.com.readowl.readowl.shared.persistence;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.community.domain.BookRating;
import br.com.readowl.readowl.community.domain.BookRatingId;
import br.com.readowl.readowl.community.domain.Comment;
import br.com.readowl.readowl.community.domain.CommentLike;
import br.com.readowl.readowl.community.domain.CommentLikeId;
import br.com.readowl.readowl.library.domain.BookFollow;
import br.com.readowl.readowl.library.domain.BookFollowId;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.publishing.domain.ChapterDraft;
import br.com.readowl.readowl.publishing.domain.ChapterRevision;
import br.com.readowl.readowl.support.IntegrationTest;
import br.com.readowl.readowl.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class EntityMappingIntegrationTest {

    private static final String DOCUMENT = "{\"type\": \"doc\", \"content\": []}";
    private static final String CHANGED_DOCUMENT = "{\"type\": \"doc\", \"content\": [{\"type\": \"paragraph\"}]}";

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void generatesTimeOrderedIdsAndNormalizesUserIdentity() {
        String unique = uniqueText();

        User user = persist(new User("Leitora", "U" + unique, "Pessoa" + unique + "@Example.COM ", null));

        assertThat(user.getId().version()).isEqualTo(7);
        assertThat(user.getUsername()).isEqualTo(("u" + unique).toLowerCase());
        assertThat(user.getEmail()).isEqualTo(("pessoa" + unique + "@example.com").toLowerCase());
        assertThat(user.getCreatedAt()).isNotNull();
    }

    @Test
    void persistsEntitiesWithCompositeKeys() {
        User author = newUser();
        User reader = newUser();
        Book book = newBook(author);
        Comment comment = persist(new Comment("Muito bom", null, reader, book, null, null));

        persist(new BookFollow(reader, book));
        persist(new BookRating(reader, book, 5));
        persist(new CommentLike(author, comment));

        transaction.executeWithoutResult(status -> {
            assertThat(entityManager.find(BookFollow.class, new BookFollowId(reader.getId(), book.getId()))).isNotNull();
            assertThat(entityManager.find(BookRating.class, new BookRatingId(reader.getId(), book.getId())).getScore())
                    .isEqualTo(5);
            assertThat(entityManager.find(CommentLike.class, new CommentLikeId(author.getId(), comment.getId())))
                    .isNotNull();
        });
    }

    @Test
    void loadsAssociationsLazily() {
        User author = newUser();
        Book book = newBook(author);
        Chapter chapter = persist(new Chapter(book, null, "Um", "um", 1));
        Comment parent = persist(new Comment("Pergunta", null, author, book, chapter, null));
        Comment reply = persist(new Comment("Resposta", null, author, book, chapter, parent));

        transaction.executeWithoutResult(status -> {
            Comment loaded = entityManager.find(Comment.class, reply.getId());

            assertThat(Hibernate.isInitialized(loaded.getUser())).isFalse();
            assertThat(Hibernate.isInitialized(loaded.getBook())).isFalse();
            assertThat(Hibernate.isInitialized(loaded.getChapter())).isFalse();
            assertThat(Hibernate.isInitialized(loaded.getParent())).isFalse();
        });
    }

    @Test
    void savingDraftDoesNotChangePublishedRevision() {
        User author = newUser();
        Chapter chapter = persist(new Chapter(newBook(author), null, "Um", "um", 1));
        ChapterDraft draft = persist(new ChapterDraft(chapter, DOCUMENT, 1));
        ChapterRevision revision = persist(new ChapterRevision(chapter, 1, DOCUMENT, 1, "", "", author));
        transaction.executeWithoutResult(status ->
                entityManager.find(Chapter.class, chapter.getId())
                        .publish(entityManager.find(ChapterRevision.class, revision.getId())));

        transaction.executeWithoutResult(status ->
                entityManager.find(ChapterDraft.class, draft.getChapterId()).replaceDocument(CHANGED_DOCUMENT, 1));

        transaction.executeWithoutResult(status -> {
            Chapter loaded = entityManager.find(Chapter.class, chapter.getId());
            assertThat(loaded.getPublishedRevision().getId()).isEqualTo(revision.getId());
            assertThat(loaded.getPublishedRevision().getDocument()).isEqualTo(DOCUMENT);
            assertThat(entityManager.find(ChapterDraft.class, draft.getChapterId()).getDocument())
                    .isEqualTo(CHANGED_DOCUMENT);
        });
    }

    @Test
    void rejectsStaleDraftUpdate() {
        Chapter chapter = persist(new Chapter(newBook(newUser()), null, "Um", "um", 1));
        persist(new ChapterDraft(chapter, DOCUMENT, 1));
        ChapterDraft stale = transaction.execute(status -> entityManager.find(ChapterDraft.class, chapter.getId()));
        transaction.executeWithoutResult(status ->
                entityManager.find(ChapterDraft.class, chapter.getId()).replaceDocument(CHANGED_DOCUMENT, 1));

        stale.replaceDocument(DOCUMENT, 1);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> entityManager.merge(stale)))
                .isInstanceOfAny(OptimisticLockingFailureException.class, OptimisticLockException.class);
    }

    @Test
    void entityUpdateDoesNotOverwriteAtomicViewCounter() {
        User author = newUser();
        Chapter chapter = persist(new Chapter(newBook(author), null, "Um", "um", 1));
        ChapterRevision revision = persist(new ChapterRevision(chapter, 1, DOCUMENT, 1, "", "", author));

        transaction.executeWithoutResult(status -> {
            Chapter loaded = entityManager.find(Chapter.class, chapter.getId());
            jdbc.update("UPDATE chapters SET view_count = view_count + 1 WHERE id = ?", chapter.getId());
            loaded.publish(entityManager.find(ChapterRevision.class, revision.getId()));
        });

        Long viewCount = jdbc.queryForObject("SELECT view_count FROM chapters WHERE id = ?", Long.class, chapter.getId());
        assertThat(viewCount).isEqualTo(1L);
    }

    private User newUser() {
        String unique = uniqueText();
        return persist(new User("Pessoa", "u" + unique, unique + "@example.com", null));
    }

    private Book newBook(User author) {
        return persist(new Book("Obra", "TST", "obra-" + uniqueText(), "Sinopse", author));
    }

    private <T> T persist(T entity) {
        return transaction.execute(status -> {
            entityManager.persist(entity);
            return entity;
        });
    }

    private static String uniqueText() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
