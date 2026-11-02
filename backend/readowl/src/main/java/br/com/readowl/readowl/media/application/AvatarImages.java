package br.com.readowl.readowl.media.application;

import br.com.readowl.readowl.shared.exception.ApiException;

/**
 * Regras da foto de perfil. A validação e a reescrita do arquivo estão em {@link ImageFiles}.
 */
public final class AvatarImages {

    public static final int MAX_SIDE = 300;
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final String CONTENT_TYPE = ImageFiles.CONTENT_TYPE;

    private AvatarImages() {
    }

    public static byte[] normalize(byte[] file) {
        ImageFiles.Size limit = new ImageFiles.Size(MAX_SIDE, MAX_SIDE);
        return ImageFiles.normalize(file, size -> {
            if (size.width() > MAX_SIDE || size.height() > MAX_SIDE) {
                throw ApiException.badRequest("AVATAR_TOO_LARGE", "A foto pode ter no máximo " + MAX_SIDE + "x" + MAX_SIDE + " pixels.");
            }
        }, limit);
    }
}
