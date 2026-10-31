package br.com.readowl.readowl.publishing.api;

import br.com.readowl.readowl.book.domain.BookStatus;
import br.com.readowl.readowl.publishing.application.CatalogService;
import br.com.readowl.readowl.publishing.application.ReadingService;
import br.com.readowl.readowl.shared.api.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Rotas públicas de leitura.
 */
@RestController
@RequestMapping("/api/v1/books")
class ReadingController {

    private final CatalogService catalog;
    private final ReadingService reading;

    ReadingController(CatalogService catalog, ReadingService reading) {
        this.catalog = catalog;
        this.reading = reading;
    }

    @GetMapping
    PageResponse<CatalogService.CatalogEntry> catalog(@RequestParam(required = false) String query,
                                                      @RequestParam(required = false) List<UUID> genre,
                                                      @RequestParam(required = false) List<String> tag,
                                                      @RequestParam(required = false) BookStatus status,
                                                      @RequestParam(required = false) String author,
                                                      @RequestParam(required = false) String sort,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return catalog.search(new CatalogService.CatalogSearch(query,
                genre == null ? List.of() : genre, tag == null ? List.of() : tag, status, author, sort, page, size));
    }

    @GetMapping("/{slug}")
    ReadingService.PublicBook book(@PathVariable String slug) {
        return reading.book(slug);
    }

    @GetMapping("/{slug}/chapters/{chapterSlug}")
    ReadingService.ChapterReading chapter(@PathVariable String slug, @PathVariable String chapterSlug) {
        return reading.chapter(slug, chapterSlug);
    }
}
