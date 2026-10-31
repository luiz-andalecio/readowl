package br.com.readowl.readowl.genre.infrastructure;

import br.com.readowl.readowl.genre.domain.Genre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface GenreRepository extends JpaRepository<Genre, UUID> {

    @Query("select count(g) > 0 from Genre g where lower(g.name) = lower(:name) and g.id <> :excludedId")
    boolean existsByNameExcluding(String name, UUID excludedId);

    @Query("select count(g) > 0 from Genre g where lower(g.name) = lower(:name)")
    boolean existsByName(String name);
}
