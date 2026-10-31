package br.com.readowl.readowl.genre.infrastructure;

import br.com.readowl.readowl.genre.domain.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    /**
     * Seguro para autores criando a mesma tag ao mesmo tempo: quem chega depois não recebe erro.
     */
    @Modifying
    @Query(value = """
            INSERT INTO tags (id, version, created_at, updated_at, name)
            VALUES (uuidv7(), 0, now(), now(), :name)
            ON CONFLICT (lower(name)) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(String name);

    @Query("select t from Tag t where lower(t.name) in :lowerNames")
    List<Tag> findByLowerNames(Collection<String> lowerNames);

    @Query("select t from Tag t where lower(t.name) like :prefix escape '\\' order by t.name")
    List<Tag> searchByPrefix(String prefix, Pageable pageable);

    @Query("select count(t) > 0 from Tag t where lower(t.name) = lower(:name)")
    boolean existsByName(String name);

    @Query("select count(t) > 0 from Tag t where lower(t.name) = lower(:name) and t.id <> :excludedId")
    boolean existsByNameExcluding(String name, UUID excludedId);
}
