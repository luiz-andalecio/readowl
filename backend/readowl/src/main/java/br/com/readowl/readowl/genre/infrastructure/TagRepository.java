package br.com.readowl.readowl.genre.infrastructure;

import br.com.readowl.readowl.genre.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    /**
     * Só tags de obras no ar: uma tag que alguém acabou de criar não aparece para os outros até ter esse respaldo.
     * Em SQL porque este módulo não conhece o de obras.
     */
    @Query(value = """
            SELECT t.* FROM tags t
            WHERE lower(t.name) LIKE :prefix ESCAPE '\\'
              AND EXISTS (SELECT 1 FROM book_tags bt JOIN books b ON b.id = bt.book_id
                          WHERE bt.tag_id = t.id AND b.publication_state = 'PUBLISHED')
            ORDER BY t.name LIMIT :limit
            """, nativeQuery = true)
    List<Tag> suggest(String prefix, int limit);

    @Modifying
    @Query(value = "DELETE FROM book_tags WHERE tag_id = :tagId", nativeQuery = true)
    void removeFromBooks(UUID tagId);

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

    @Query("select count(t) > 0 from Tag t where lower(t.name) = lower(:name)")
    boolean existsByName(String name);

    @Query("select count(t) > 0 from Tag t where lower(t.name) = lower(:name) and t.id <> :excludedId")
    boolean existsByNameExcluding(String name, UUID excludedId);
}
