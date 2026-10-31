package br.com.readowl.readowl.publishing.domain;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "volumes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Volume extends BaseEntity {

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private int displayOrder;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    public Volume(Book book, String title, int displayOrder) {
        this.book = book;
        this.title = title;
        this.displayOrder = displayOrder;
    }

    public void rename(String title) {
        this.title = title;
    }

    public void moveTo(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}
