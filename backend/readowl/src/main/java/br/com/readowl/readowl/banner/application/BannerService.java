package br.com.readowl.readowl.banner.application;

import br.com.readowl.readowl.banner.domain.Banner;
import br.com.readowl.readowl.banner.infrastructure.BannerRepository;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.media.application.ExternalImages;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.text.ExternalUrls;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * As imagens são endereços externos até a etapa de mídia. Só HTTPS: bloqueia javascript: e outros esquemas
 * que chegariam a um link ou imagem da página inicial.
 */
@Service
@Transactional
public class BannerService {

    private final BannerRepository banners;
    private final ExternalImages images;

    BannerService(BannerRepository banners, ExternalImages images) {
        this.banners = banners;
        this.images = images;
    }

    public record BannerData(String name, String imageUrl, String linkUrl, int position, boolean enabled,
                             Instant activeFrom, Instant activeUntil) {
    }

    public record PublicBanner(UUID id, String name, String imageUrl, String linkUrl, int position) {
    }

    public record BannerView(UUID id, String name, String imageUrl, String linkUrl, int position, boolean enabled,
                             Instant activeFrom, Instant activeUntil, long version, Instant createdAt, Instant updatedAt) {

        static BannerView from(Banner banner) {
            return new BannerView(banner.getId(), banner.getName(), banner.getImageUrl(), banner.getLinkUrl(),
                    banner.getPosition(), banner.isEnabled(), banner.getActiveFrom(), banner.getActiveUntil(),
                    banner.getVersion(), banner.getCreatedAt(), banner.getUpdatedAt());
        }
    }

    @Transactional(readOnly = true)
    public List<PublicBanner> listActive() {
        return banners.findActive(Instant.now()).stream()
                .map(banner -> new PublicBanner(banner.getId(), banner.getName(), banner.getImageUrl(),
                        banner.getLinkUrl(), banner.getPosition()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<BannerView> list(Pageable pageable) {
        return PageResponse.from(banners.findAll(pageable).map(BannerView::from));
    }

    @Transactional(readOnly = true)
    public BannerView get(UUID id) {
        return BannerView.from(require(id));
    }

    public BannerView create(BannerData data) {
        validate(data);
        return BannerView.from(banners.saveAndFlush(new Banner(data.name().trim(), data.imageUrl().trim(),
                data.linkUrl().trim(), data.position(), data.enabled(), data.activeFrom(), data.activeUntil())));
    }

    /**
     * Substitui todos os campos, o que permite limpar o período. Recebe a versão lida para não desfazer a
     * alteração de outro administrador.
     */
    public BannerView update(UUID id, long version, BannerData data) {
        validate(data);
        Banner banner = require(id);
        if (banner.getVersion() != version) {
            throw ApiException.conflict("BANNER_CONFLICT", "Este banner foi alterado por outra pessoa. Recarregue e tente novamente.")
                    .withProperty("currentVersion", banner.getVersion());
        }
        banner.update(data.name().trim(), data.imageUrl().trim(), data.linkUrl().trim(), data.position(),
                data.enabled(), data.activeFrom(), data.activeUntil());
        return BannerView.from(banners.saveAndFlush(banner));
    }

    public void delete(UUID id) {
        banners.delete(require(id));
    }

    private Banner require(UUID id) {
        return banners.findById(id).orElseThrow(() -> ApiException.notFound("BANNER_NOT_FOUND", "Banner não encontrado."));
    }

    private void validate(BannerData data) {
        if (!images.isAllowed(data.imageUrl())) {
            throw ApiException.badRequest("INVALID_BANNER_URL", "Use uma imagem com https://, em um dos sites permitidos.");
        }
        requireHttps(data.linkUrl());
        if (data.activeFrom() != null && data.activeUntil() != null && !data.activeUntil().isAfter(data.activeFrom())) {
            throw ApiException.badRequest("INVALID_BANNER_PERIOD", "O fim do período deve ser depois do início.");
        }
    }

    private static void requireHttps(String url) {
        if (!ExternalUrls.isHttps(url)) {
            throw ApiException.badRequest("INVALID_BANNER_URL", "Use endereços completos com https://.");
        }
    }
}
