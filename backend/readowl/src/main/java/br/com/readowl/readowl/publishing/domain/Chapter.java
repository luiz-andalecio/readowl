package br.com.readowl.readowl.publishing.domain;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "chapters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Chapter extends BaseEntity {

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false, length = 200)
    private String title;

    @NotBlank
    @Size(max = 220)
    @Column(nullable = false, length = 220)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChapterState state = ChapterState.DRAFT;

    @Column(nullable = false)
    private int displayOrder;

    // Alterado apenas por UPDATE atômico: não pode ser sobrescrito por uma entidade desatualizada.
    @Column(nullable = false, updatable = false)
    private long viewCount;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "volume_id")
    private Volume volume;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_revision_id")
    private ChapterRevision publishedRevision;

    private Instant moderatedAt;

    public Chapter(Book book, Volume volume, String title, String slug, int displayOrder) {
        this.book = book;
        this.volume = volume;
        this.title = title;
        this.slug = slug;
        this.displayOrder = displayOrder;
    }

    /**
     * O slug acompanha o título, que define o endereço do capítulo.
     */
    public void rename(String title, String slug) {
        this.title = title;
        this.slug = slug;
    }

    public void moveTo(Volume volume, int displayOrder) {
        this.volume = volume;
        this.displayOrder = displayOrder;
    }

    public boolean isPublished() {
        return state == ChapterState.PUBLISHED;
    }

    public void publish(ChapterRevision revision) {
        this.publishedRevision = revision;
        this.state = ChapterState.PUBLISHED;
    }

    /**
     * Mantém a referência à revisão: republicar sem alterações não precisa criar outra.
     */
    public void unpublish() {
        this.state = ChapterState.UNPUBLISHED;
    }

    public boolean isModerated() {
        return moderatedAt != null;
    }

    /**
     * Tira o capítulo do ar. Restaurar não o republica: isso continua sendo decisão do autor.
     */
    public void hideByModeration(Instant at) {
        this.moderatedAt = at;
        if (isPublished()) {
            unpublish();
        }
    }

    public void restoreFromModeration() {
        this.moderatedAt = null;
    }
}
