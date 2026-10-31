package br.com.readowl.readowl.genre.api;

import br.com.readowl.readowl.genre.application.TaxonomyItem;
import br.com.readowl.readowl.genre.application.TaxonomyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
class AdminTaxonomyController {

    private final TaxonomyService taxonomy;

    AdminTaxonomyController(TaxonomyService taxonomy) {
        this.taxonomy = taxonomy;
    }

    @PostMapping("/genres")
    @ResponseStatus(HttpStatus.CREATED)
    TaxonomyItem createGenre(@Valid @RequestBody GenreRequest body) {
        return taxonomy.createGenre(body.name());
    }

    @PatchMapping("/genres/{id}")
    TaxonomyItem renameGenre(@PathVariable UUID id, @Valid @RequestBody GenreRequest body) {
        return taxonomy.renameGenre(id, body.name());
    }

    @DeleteMapping("/genres/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteGenre(@PathVariable UUID id) {
        taxonomy.deleteGenre(id);
    }

    @PostMapping("/tags")
    @ResponseStatus(HttpStatus.CREATED)
    TaxonomyItem createTag(@Valid @RequestBody TagRequest body) {
        return taxonomy.createTag(body.name());
    }

    @PatchMapping("/tags/{id}")
    TaxonomyItem renameTag(@PathVariable UUID id, @Valid @RequestBody TagRequest body) {
        return taxonomy.renameTag(id, body.name());
    }

    @DeleteMapping("/tags/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTag(@PathVariable UUID id) {
        taxonomy.deleteTag(id);
    }

    record GenreRequest(@NotBlank @Size(max = 100) String name) {
    }

    record TagRequest(@NotBlank @Size(max = 50) String name) {
    }
}
