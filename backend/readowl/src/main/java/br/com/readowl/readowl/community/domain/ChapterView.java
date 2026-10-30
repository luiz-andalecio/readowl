package br.com.readowl.readowl.community.domain;

import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Estado de deduplicação por usuário e capítulo, não um evento por visita.
 * A escrita é feita por upsert atômico para garantir a janela de contagem entre instâncias.
 */
@Entity
@Table(name = "chapter_views")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChapterView {

    @EmbeddedId
    private ChapterViewId id;

    @MapsId("userId")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @MapsId("chapterId")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id", nullable = false)
    private Chapter chapter;

    @Column(nullable = false)
    private Instant lastCountedAt;
}
