package br.com.readowl.readowl.library.domain;

import br.com.readowl.readowl.publishing.domain.Chapter;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Onde o usuário parou em uma obra. Só é gravado por upsert atômico: a entidade serve para leitura.
 */
@Entity
@Immutable
@Table(name = "reading_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReadingProgress {

    @EmbeddedId
    private ReadingProgressId id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id", nullable = false)
    private Chapter chapter;

    @Column(nullable = false)
    private int percent;

    @Column(nullable = false)
    private Instant updatedAt;
}
