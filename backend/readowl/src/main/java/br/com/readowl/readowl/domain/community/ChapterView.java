package br.com.readowl.readowl.domain.community;

import br.com.readowl.readowl.domain.publishing.Chapter;
import br.com.readowl.readowl.domain.user.User;
import br.com.readowl.readowl.domain.shared.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chapter_views")
@Getter
@Setter
@NoArgsConstructor
public class ChapterView extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "chapter_id", nullable = false)
    private Chapter chapter;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}