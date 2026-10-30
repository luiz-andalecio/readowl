package br.com.readowl.readowl.book.domain;

import br.com.readowl.readowl.genre.domain.Genre;
import br.com.readowl.readowl.genre.domain.Tag;
import br.com.readowl.readowl.shared.domain.BaseEntity;
import br.com.readowl.readowl.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "books")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Book extends BaseEntity {

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false, length = 200)
    private String title;

    @NotBlank
    @Size(max = 220)
    @Column(nullable = false, updatable = false, length = 220)
    private String slug;

    @NotBlank
    @Size(max = 2000)
    @Column(nullable = false, length = 2000)
    private String synopsis;

    @Size(max = 50)
    @Column(length = 50)
    private String releaseFrequency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookStatus status = BookStatus.ONGOING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PublicationState publicationState = PublicationState.DRAFT;

    // Alterado apenas por UPDATE atômico: não pode ser sobrescrito por uma entidade desatualizada.
    @Column(nullable = false, updatable = false)
    private long viewCount;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @ManyToMany
    @JoinTable(name = "book_genres",
            joinColumns = @JoinColumn(name = "book_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id"))
    private Set<Genre> genres = new HashSet<>();

    @ManyToMany
    @JoinTable(name = "book_tags",
            joinColumns = @JoinColumn(name = "book_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();

    public Book(String title, String slug, String synopsis, User author) {
        this.title = title;
        this.slug = slug;
        this.synopsis = synopsis;
        this.author = author;
    }
}
