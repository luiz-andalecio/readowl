package br.com.readowl.readowl.book.domain;

import br.com.readowl.readowl.genre.domain.Genre;
import br.com.readowl.readowl.genre.domain.Tag;
import br.com.readowl.readowl.shared.domain.BaseEntity;
import br.com.readowl.readowl.shared.text.Slugs;
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

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
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
    @Size(max = 10)
    @Column(nullable = false, length = 10)
    private String acronym;

    @NotBlank
    @Size(max = 220)
    @Column(nullable = false, length = 220)
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

    private Instant lastChapterPublishedAt;

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

    public Book(String title, String acronym, String slug, String synopsis, User author) {
        this.title = title;
        this.acronym = normalizeAcronym(acronym);
        this.slug = slug;
        this.synopsis = synopsis;
        this.author = author;
    }

    public static String normalizeAcronym(String acronym) {
        return acronym.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * A sigla diferencia obras de mesmo título: SRA + "Remanescente da Aura" vira sra-remanescente-da-aura.
     */
    public static String slugFor(String acronym, String title) {
        String prefix = normalizeAcronym(acronym).toLowerCase(Locale.ROOT);
        String titleSlug = Slugs.slugify(title);
        return titleSlug.isEmpty() ? prefix : prefix + "-" + titleSlug;
    }

    /**
     * Título e sigla determinam o endereço da obra, então mudam junto com o slug.
     */
    public void rename(String title, String acronym) {
        this.title = title;
        this.acronym = normalizeAcronym(acronym);
        this.slug = slugFor(acronym, title);
    }

    public void updateSynopsis(String synopsis) {
        this.synopsis = synopsis;
    }

    public void updateReleaseFrequency(String releaseFrequency) {
        this.releaseFrequency = releaseFrequency;
    }

    public void updateStatus(BookStatus status) {
        this.status = status;
    }

    public void replaceGenres(Set<Genre> genres) {
        this.genres.clear();
        this.genres.addAll(genres);
    }

    public void replaceTags(Set<Tag> tags) {
        this.tags.clear();
        this.tags.addAll(tags);
    }

    public void markChapterPublished(Instant at) {
        lastChapterPublishedAt = at;
    }

    public boolean isPublished() {
        return publicationState == PublicationState.PUBLISHED;
    }

    public void publish() {
        publicationState = PublicationState.PUBLISHED;
    }

    public void unpublish() {
        publicationState = PublicationState.UNPUBLISHED;
    }
}
