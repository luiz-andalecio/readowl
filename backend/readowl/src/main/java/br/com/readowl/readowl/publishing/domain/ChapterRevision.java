package br.com.readowl.readowl.publishing.domain;

import br.com.readowl.readowl.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "chapter_revisions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChapterRevision {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id", nullable = false)
    private Chapter chapter;

    @Column(nullable = false)
    private int revisionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String document;

    @Column(nullable = false)
    private int formatVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String plainText;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String html;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public ChapterRevision(Chapter chapter, int revisionNumber, String document, int formatVersion,
                           String plainText, String html, User createdBy) {
        this.chapter = chapter;
        this.revisionNumber = revisionNumber;
        this.document = document;
        this.formatVersion = formatVersion;
        this.plainText = plainText;
        this.html = html;
        this.createdBy = createdBy;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
