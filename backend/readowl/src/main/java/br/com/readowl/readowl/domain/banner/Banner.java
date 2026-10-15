package br.com.readowl.readowl.domain.banner;

import br.com.readowl.readowl.domain.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "banners")
@Getter
@Setter
@NoArgsConstructor
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
}