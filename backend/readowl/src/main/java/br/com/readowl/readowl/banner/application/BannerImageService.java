package br.com.readowl.readowl.banner.application;

import br.com.readowl.readowl.banner.application.BannerService.BannerView;
import br.com.readowl.readowl.banner.domain.Banner;
import br.com.readowl.readowl.banner.infrastructure.BannerRepository;
import br.com.readowl.readowl.media.application.StoredImageType;
import br.com.readowl.readowl.media.application.StoredImages;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.UUID;

/**
 * Imagem do banner da página inicial. O download e o envio ficam fora de transação. Se salvar falhar, o arquivo novo é apagado.
 */
@Service
public class BannerImageService {

    private final BannerRepository banners;
    private final StoredImages images;
    private final TransactionOperations transaction;

    BannerImageService(BannerRepository banners, StoredImages images, TransactionOperations transaction) {
        this.banners = banners;
        this.images = images;
        this.transaction = transaction;
    }

    public BannerView replaceWithFile(UUID id, byte[] file) {
        requireExisting(id);
        return attach(id, images.fromFile(StoredImageType.HOME_BANNER, id, file));
    }

    public BannerView replaceWithLink(UUID id, String url) {
        requireExisting(id);
        return attach(id, images.fromLink(StoredImageType.HOME_BANNER, id, url));
    }

    private void requireExisting(UUID id) {
        if (!banners.existsById(id)) {
            throw BannerService.notFound();
        }
    }

    private BannerView attach(UUID id, StoredImages.Stored stored) {
        try {
            return transaction.execute(status -> {
                Banner banner = banners.findById(id).orElseThrow(BannerService::notFound);
                images.release(banner.getImageKey());
                banner.changeImage(stored.key(), stored.url());
                return BannerView.from(banners.saveAndFlush(banner));
            });
        } catch (RuntimeException ex) {
            images.release(stored.key());
            throw ex;
        }
    }
}
