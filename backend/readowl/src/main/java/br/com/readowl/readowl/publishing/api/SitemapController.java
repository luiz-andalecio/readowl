package br.com.readowl.readowl.publishing.api;

import br.com.readowl.readowl.publishing.infrastructure.SitemapQueries;
import br.com.readowl.readowl.shared.api.PublicCache;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.time.format.DateTimeFormatter;

/**
 * Sitemap para buscadores, fora de /api porque a URL é pública e fixa. Só traz o que um visitante pode abrir, então
 * deixa de fora obras adultas. Fica uma hora em cache, porque buscadores não precisam de nada mais recente.
 */
@RestController
class SitemapController {

    private static final int ONE_HOUR = 3600;
    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    private static final String NAMESPACE = "xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"";

    private final SitemapQueries queries;
    private final ReadowlProperties properties;

    SitemapController(SitemapQueries queries, ReadowlProperties properties) {
        this.queries = queries;
        this.properties = properties;
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @PublicCache(seconds = ONE_HOUR)
    String index() {
        StringBuilder xml = new StringBuilder(HEADER).append("<sitemapindex ").append(NAMESPACE).append(">\n");
        for (int file = 1; file <= queries.files(); file++) {
            xml.append("<sitemap><loc>").append(location("/sitemap/" + file + ".xml")).append("</loc></sitemap>\n");
        }
        return xml.append("</sitemapindex>\n").toString();
    }

    @GetMapping(value = "/sitemap/{file:\\d{1,6}}.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @PublicCache(seconds = ONE_HOUR)
    String urls(@PathVariable int file) {
        if (file < 1 || file > queries.files()) {
            throw ApiException.notFound("SITEMAP_NOT_FOUND", "Este arquivo do sitemap não existe.");
        }
        StringBuilder xml = new StringBuilder(HEADER).append("<urlset ").append(NAMESPACE).append(">\n");
        for (SitemapQueries.Url url : queries.page(file)) {
            xml.append("<url><loc>").append(location(url.path())).append("</loc><lastmod>")
                    .append(DateTimeFormatter.ISO_INSTANT.format(url.updatedAt())).append("</lastmod></url>\n");
        }
        return xml.append("</urlset>\n").toString();
    }

    private String location(String path) {
        return HtmlUtils.htmlEscape(properties.pageUrl(path), "UTF-8");
    }
}
