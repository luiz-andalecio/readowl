package br.com.readowl.readowl.library.domain;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "book_follows")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookFollow {

    @EmbeddedId
    private BookFollowId id;

    @MapsId("userId")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @MapsId("bookId")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public BookFollow(User user, Book book) {
        this.id = new BookFollowId(user.getId(), book.getId());
        this.user = user;
        this.book = book;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
