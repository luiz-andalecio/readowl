package br.com.readowl.readowl.community.domain;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.shared.domain.BaseEntity;
import br.com.readowl.readowl.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseEntity {

    public static final int MAX_CONTENT_LENGTH = 5000;
    public static final String PURGED_CONTENT = "[texto removido]";

    @NotBlank
    @Size(max = MAX_CONTENT_LENGTH)
    @Column(nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    // Fica em outro site: guardamos só o endereço.
    @Size(max = 1000)
    @Column(length = 1000)
    private String imageUrl;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id")
    private Chapter chapter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Comment parent;

    private Instant deletedAt;

    private Instant moderatedAt;

    private Instant editedAt;

    // Definido só pela tarefa de retenção, em SQL, depois do prazo de um comentário excluído.
    @Column(insertable = false, updatable = false)
    private Instant contentPurgedAt;

    public Comment(String content, String imageUrl, User user, Book book, Chapter chapter, Comment parent) {
        this.content = content;
        this.imageUrl = imageUrl;
        this.user = user;
        this.book = book;
        this.chapter = chapter;
        this.parent = parent;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void edit(String content, String imageUrl, Instant at) {
        if (!content.equals(this.content) || !Objects.equals(imageUrl, this.imageUrl)) {
            this.content = content;
            this.imageUrl = imageUrl;
            this.editedAt = at;
        }
    }

    /**
     * O texto é mantido para a moderação, mas deixa de ser exibido.
     */
    public void delete(Instant at) {
        this.deletedAt = at;
    }

    public boolean isContentPurged() {
        return contentPurgedAt != null;
    }

    public boolean isModerated() {
        return moderatedAt != null;
    }

    public void hideByModeration(Instant at) {
        this.deletedAt = at;
        this.moderatedAt = at;
    }

    public void restoreFromModeration() {
        this.deletedAt = null;
        this.moderatedAt = null;
    }
}
