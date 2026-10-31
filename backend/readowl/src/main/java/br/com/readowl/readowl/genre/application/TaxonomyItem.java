package br.com.readowl.readowl.genre.application;

import br.com.readowl.readowl.genre.domain.Genre;
import br.com.readowl.readowl.genre.domain.Tag;

import java.util.UUID;

public record TaxonomyItem(UUID id, String name) {

    public static TaxonomyItem from(Genre genre) {
        return new TaxonomyItem(genre.getId(), genre.getName());
    }

    public static TaxonomyItem from(Tag tag) {
        return new TaxonomyItem(tag.getId(), tag.getName());
    }
}
