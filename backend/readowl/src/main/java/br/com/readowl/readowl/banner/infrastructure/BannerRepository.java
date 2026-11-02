package br.com.readowl.readowl.banner.infrastructure;

import br.com.readowl.readowl.banner.domain.Banner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BannerRepository extends JpaRepository<Banner, UUID> {

    /**
     * Um período sem início ou sem fim fica em aberto naquele lado. Um banner ainda sem imagem não aparece.
     */
    @Query("""
            select b from Banner b
            where b.enabled = true
              and b.imageUrl is not null
              and (b.activeFrom is null or b.activeFrom <= :now)
              and (b.activeUntil is null or b.activeUntil > :now)
            order by b.position, b.id
            """)
    List<Banner> findActive(Instant now);
}
