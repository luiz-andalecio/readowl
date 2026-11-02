package br.com.readowl.readowl.media.application;

import br.com.readowl.readowl.media.application.ImageFiles.Size;
import br.com.readowl.readowl.shared.exception.ApiException;

/**
 * Imagens que aceitam arquivo ou link e ficam sempre no armazenamento. A proporção exigida é a do tamanho mínimo.
 */
public enum StoredImageType {

    COVER("covers", "INVALID_COVER_URL", new Size(600, 800), new Size(1200, 1600)),
    BOOK_BANNER("book-banners", "INVALID_BANNER_URL", new Size(1200, 400), new Size(2400, 800)),
    HOME_BANNER("banners", "INVALID_BANNER_URL", new Size(1200, 400), new Size(2400, 800));

    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    private static final int MAX_SIDE = 6000;
    private static final int PROPORTION_TOLERANCE_PERCENT = 2;

    private final String folder;
    private final String invalidUrlCode;
    private final Size minimum;
    private final Size stored;

    StoredImageType(String folder, String invalidUrlCode, Size minimum, Size stored) {
        this.folder = folder;
        this.invalidUrlCode = invalidUrlCode;
        this.minimum = minimum;
        this.stored = stored;
    }

    String folder() {
        return folder;
    }

    String invalidUrlCode() {
        return invalidUrlCode;
    }

    Size stored() {
        return stored;
    }

    void check(Size size) {
        if (size.width() > MAX_SIDE || size.height() > MAX_SIDE) {
            throw ApiException.badRequest("IMAGE_TOO_LARGE", "A imagem pode ter no máximo " + MAX_SIDE + " pixels de largura ou altura.");
        }
        if (size.width() < minimum.width() || size.height() < minimum.height()) {
            throw ApiException.badRequest("IMAGE_TOO_SMALL", "A imagem precisa ter no mínimo " + minimum.width() + "x" + minimum.height() + " pixels.")
                    .withProperty("minWidth", minimum.width())
                    .withProperty("minHeight", minimum.height());
        }
        long expected = (long) size.height() * minimum.width();
        long actual = (long) size.width() * minimum.height();
        if (Math.abs(actual - expected) * 100 > expected * PROPORTION_TOLERANCE_PERCENT) {
            throw ApiException.badRequest("IMAGE_WRONG_PROPORTION", "A imagem precisa ter a proporção de " + minimum.width() + "x" + minimum.height() + ".")
                    .withProperty("minWidth", minimum.width())
                    .withProperty("minHeight", minimum.height());
        }
    }
}
