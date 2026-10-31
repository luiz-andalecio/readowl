package br.com.readowl.readowl.genre.application;

import br.com.readowl.readowl.genre.domain.Genre;
import br.com.readowl.readowl.genre.domain.Tag;
import br.com.readowl.readowl.genre.infrastructure.GenreRepository;
import br.com.readowl.readowl.genre.infrastructure.TagRepository;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TaxonomyService {

    private static final int TAG_SUGGESTIONS = 20;

    private final GenreRepository genres;
    private final TagRepository tags;

    TaxonomyService(GenreRepository genres, TagRepository tags) {
        this.genres = genres;
        this.tags = tags;
    }

    public List<TaxonomyItem> listGenres() {
        return genres.findAll(Sort.by("name")).stream().map(TaxonomyItem::from).toList();
    }

    public List<TaxonomyItem> searchTags(String query) {
        String prefix = escapeLike(normalize(query).toLowerCase(Locale.ROOT)) + "%";
        return tags.searchByPrefix(prefix, PageRequest.of(0, TAG_SUGGESTIONS)).stream().map(TaxonomyItem::from).toList();
    }

    public Set<Genre> requireGenres(Collection<UUID> ids) {
        Set<UUID> distinct = new HashSet<>(ids);
        List<Genre> found = genres.findAllById(distinct);
        if (found.size() != distinct.size()) {
            throw ApiException.badRequest("UNKNOWN_GENRE", "Um dos gêneros informados não existe.");
        }
        return new HashSet<>(found);
    }

    /**
     * Devolve as tags pedidas, criando as que faltam. Maiúsculas não geram duplicatas: vale a grafia já existente.
     */
    @Transactional
    public Set<Tag> resolveTags(Collection<String> names) {
        Map<String, String> byLowerName = new LinkedHashMap<>();
        names.stream().map(TaxonomyService::normalize)
                .forEach(name -> byLowerName.putIfAbsent(name.toLowerCase(Locale.ROOT), name));
        if (byLowerName.isEmpty()) {
            return new HashSet<>();
        }
        byLowerName.values().forEach(tags::insertIfAbsent);
        return new HashSet<>(tags.findByLowerNames(byLowerName.keySet()));
    }

    @Transactional
    public TaxonomyItem createGenre(String name) {
        String normalized = normalize(name);
        if (genres.existsByName(normalized)) {
            throw nameTaken("GENRE_NAME_TAKEN");
        }
        return TaxonomyItem.from(genres.saveAndFlush(new Genre(normalized)));
    }

    @Transactional
    public TaxonomyItem renameGenre(UUID id, String name) {
        String normalized = normalize(name);
        Genre genre = genres.findById(id)
                .orElseThrow(() -> ApiException.notFound("GENRE_NOT_FOUND", "Gênero não encontrado."));
        if (genres.existsByNameExcluding(normalized, id)) {
            throw nameTaken("GENRE_NAME_TAKEN");
        }
        genre.rename(normalized);
        return TaxonomyItem.from(genres.saveAndFlush(genre));
    }

    @Transactional
    public void deleteGenre(UUID id) {
        Genre genre = genres.findById(id)
                .orElseThrow(() -> ApiException.notFound("GENRE_NOT_FOUND", "Gênero não encontrado."));
        try {
            genres.delete(genre);
            genres.flush();
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("GENRE_IN_USE", "Este gênero está em uso por alguma obra.");
        }
    }

    @Transactional
    public TaxonomyItem createTag(String name) {
        String normalized = normalize(name);
        if (tags.existsByName(normalized)) {
            throw nameTaken("TAG_NAME_TAKEN");
        }
        return TaxonomyItem.from(tags.saveAndFlush(new Tag(normalized)));
    }

    @Transactional
    public TaxonomyItem renameTag(UUID id, String name) {
        String normalized = normalize(name);
        Tag tag = tags.findById(id).orElseThrow(() -> ApiException.notFound("TAG_NOT_FOUND", "Tag não encontrada."));
        if (tags.existsByNameExcluding(normalized, id)) {
            throw nameTaken("TAG_NAME_TAKEN");
        }
        tag.rename(normalized);
        return TaxonomyItem.from(tags.saveAndFlush(tag));
    }

    @Transactional
    public void deleteTag(UUID id) {
        Tag tag = tags.findById(id).orElseThrow(() -> ApiException.notFound("TAG_NOT_FOUND", "Tag não encontrada."));
        try {
            tags.delete(tag);
            tags.flush();
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("TAG_IN_USE", "Esta tag está em uso por alguma obra.");
        }
    }

    private static ApiException nameTaken(String code) {
        return ApiException.conflict(code, "Já existe um item com este nome.");
    }

    private static String normalize(String name) {
        return name.trim().replaceAll("\\s+", " ");
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
