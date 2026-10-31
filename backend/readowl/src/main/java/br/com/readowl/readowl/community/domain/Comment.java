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

@Entity
@Table(name = "comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseEntity {

    public static final int MAX_CONTENT_LENGTH = 5000;

    @NotBlank
    @Size(max = MAX_CONTENT_LENGTH)
    @Column(nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

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

    public Comment(String content, User user, Book book, Chapter chapter, Comment parent) {
        this.content = content;
        this.user = user;
        this.book = book;
        this.chapter = chapter;
        this.parent = parent;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void edit(String content, Instant at) {
        if (!content.equals(this.content)) {
            this.content = content;
            this.editedAt = at;
        }
    }

    /**
     * O texto é mantido para a moderação, mas deixa de ser exibido.
     */
    public void delete(Instant at) {
        this.deletedAt = at;
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
