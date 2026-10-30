package br.com.readowl.readowl.publishing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chapter_drafts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChapterDraft {

    @Id
    private UUID chapterId;

    @MapsId
    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id")
    private Chapter chapter;

    @Version
    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String document;

    @Column(nullable = false)
    private int formatVersion;

    @Column(nullable = false)
    private Instant updatedAt;

    public ChapterDraft(Chapter chapter, String document, int formatVersion) {
        this.chapter = chapter;
        this.document = document;
        this.formatVersion = formatVersion;
    }

    public void replaceDocument(String document, int formatVersion) {
        this.document = document;
        this.formatVersion = formatVersion;
    }

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }
}
