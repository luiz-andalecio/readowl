package br.com.readowl.readowl.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Só é gravada por upsert atômico: a entidade serve para leitura.
 */
@Entity
@Immutable
@Table(name = "chapter_reactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChapterReaction {

    @EmbeddedId
    private ChapterReactionId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReactionType type;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
