package br.com.readowl.readowl.genre.api;

import br.com.readowl.readowl.genre.application.TaxonomyItem;
import br.com.readowl.readowl.genre.application.TaxonomyService;
import br.com.readowl.readowl.shared.api.PublicCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
class TaxonomyController {

    private final TaxonomyService taxonomy;

    TaxonomyController(TaxonomyService taxonomy) {
        this.taxonomy = taxonomy;
    }

    @GetMapping("/genres")
    @PublicCache(seconds = 300)
    List<TaxonomyItem> genres() {
        return taxonomy.listGenres();
    }

    @GetMapping("/tags")
    List<TaxonomyItem> tags(@RequestParam(defaultValue = "") String query) {
        return taxonomy.searchTags(query);
    }
}
