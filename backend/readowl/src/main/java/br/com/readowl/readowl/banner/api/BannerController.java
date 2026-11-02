package br.com.readowl.readowl.banner.api;

import br.com.readowl.readowl.banner.application.BannerImageService;
import br.com.readowl.readowl.banner.application.BannerService;
import br.com.readowl.readowl.banner.application.BannerService.BannerData;
import br.com.readowl.readowl.banner.application.BannerService.BannerView;
import br.com.readowl.readowl.media.api.ImageUploads;
import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.api.PublicCache;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimited;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class BannerController {

    private static final Set<String> SORTS = Set.of("position", "name", "createdAt");

    private final BannerService banners;
    private final BannerImageService images;

    BannerController(BannerService banners, BannerImageService images) {
        this.banners = banners;
        this.images = images;
    }

    record ImageLinkRequest(@NotBlank @Size(max = 1000) String url) {
    }

    record NewBannerRequest(
            @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "tem caracteres não permitidos") String name,
            @NotBlank @Size(max = 1000) String linkUrl,
            @NotNull @Min(0) Integer position,
            @NotNull Boolean enabled,
            Instant activeFrom,
            Instant activeUntil) {

        BannerData data() {
            return new BannerData(name, linkUrl, position, enabled, activeFrom, activeUntil);
        }
    }

    record UpdateBannerRequest(
            @NotNull Long version,
            @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "tem caracteres não permitidos") String name,
            @NotBlank @Size(max = 1000) String linkUrl,
            @NotNull @Min(0) Integer position,
            @NotNull Boolean enabled,
            Instant activeFrom,
            Instant activeUntil) {

        BannerData data() {
            return new BannerData(name, linkUrl, position, enabled, activeFrom, activeUntil);
        }
    }

    @GetMapping("/banners")
    @PublicCache
    List<BannerService.PublicBanner> active() {
        return banners.listActive();
    }

    @GetMapping("/admin/banners")
    PageResponse<BannerView> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(defaultValue = "position") String sort) {
        return banners.list(PageRequests.of(page, size, sort, SORTS));
    }

    @GetMapping("/admin/banners/{id}")
    BannerView get(@PathVariable UUID id) {
        return banners.get(id);
    }

    @PostMapping("/admin/banners")
    @ResponseStatus(HttpStatus.CREATED)
    BannerView create(@Valid @RequestBody NewBannerRequest body) {
        return banners.create(body.data());
    }

    @PutMapping("/admin/banners/{id}")
    BannerView update(@PathVariable UUID id, @Valid @RequestBody UpdateBannerRequest body) {
        return banners.update(id, body.version(), body.data());
    }

    @PutMapping(value = "/admin/banners/{id}/image", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    @RateLimited(RateLimitPolicy.IMAGE_ACCOUNT)
    BannerView uploadImage(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        return images.replaceWithFile(id, ImageUploads.read(request));
    }

    @PutMapping(value = "/admin/banners/{id}/image", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimitPolicy.IMAGE_ACCOUNT)
    BannerView linkImage(@PathVariable UUID id, @Valid @RequestBody ImageLinkRequest body) {
        return images.replaceWithLink(id, body.url());
    }

    @DeleteMapping("/admin/banners/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        banners.delete(id);
    }
}
