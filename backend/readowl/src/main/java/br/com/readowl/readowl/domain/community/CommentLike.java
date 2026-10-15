package br.com.readowl.readowl.domain.community;

import br.com.readowl.readowl.domain.user.User;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "comment_likes")
@Getter
@Setter
@NoArgsConstructor
public class CommentLike {

    @EmbeddedId
    private CommentLikeId id;

    @MapsId("userId")
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @MapsId("commentId")
    @ManyToOne(optional = false)
    @JoinColumn(name = "comment_id", nullable = false)
    private Comment comment;

    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}