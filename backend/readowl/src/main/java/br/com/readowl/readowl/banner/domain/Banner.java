package br.com.readowl.readowl.banner.domain;

import br.com.readowl.readowl.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "banners")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Banner extends BaseEntity {

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false, length = 200)
    private String name;

    @NotBlank
    @Size(max = 1000)
    @Column(nullable = false, length = 1000)
    private String imageUrl;

    @NotBlank
    @Size(max = 1000)
    @Column(nullable = false, length = 1000)
    private String linkUrl;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean enabled;

    private Instant activeFrom;

    private Instant activeUntil;

    public Banner(String name, String imageUrl, String linkUrl, int position, boolean enabled,
                  Instant activeFrom, Instant activeUntil) {
        update(name, imageUrl, linkUrl, position, enabled, activeFrom, activeUntil);
    }

    public final void update(String name, String imageUrl, String linkUrl, int position, boolean enabled,
                             Instant activeFrom, Instant activeUntil) {
        this.name = name;
        this.imageUrl = imageUrl;
        this.linkUrl = linkUrl;
        this.position = position;
        this.enabled = enabled;
        this.activeFrom = activeFrom;
        this.activeUntil = activeUntil;
    }
}
