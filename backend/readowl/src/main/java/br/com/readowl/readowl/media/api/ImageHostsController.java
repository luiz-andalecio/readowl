package br.com.readowl.readowl.media.api;

import br.com.readowl.readowl.media.application.ExternalImages;
import br.com.readowl.readowl.shared.api.PublicCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class ImageHostsController {

    private final ExternalImages images;

    ImageHostsController(ExternalImages images) {
        this.images = images;
    }

    @GetMapping("/api/v1/image-hosts")
    @PublicCache(seconds = 300)
    List<String> allowedHosts() {
        return images.allowedHosts();
    }
}
