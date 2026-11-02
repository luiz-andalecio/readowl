package br.com.readowl.readowl.media.api;

import br.com.readowl.readowl.media.application.StoredImageType;
import br.com.readowl.readowl.shared.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * Lê o arquivo de uma capa ou banner. O corpo é a própria imagem, sem formulário, e o limite vale mesmo sem Content-Length.
 */
public final class ImageUploads {

    private ImageUploads() {
    }

    public static byte[] read(HttpServletRequest request) throws IOException {
        byte[] file = request.getInputStream().readNBytes(StoredImageType.MAX_FILE_BYTES + 1);
        if (file.length > StoredImageType.MAX_FILE_BYTES) {
            throw ApiException.badRequest("IMAGE_FILE_TOO_LARGE", "A imagem pode ter no máximo 5 MiB.");
        }
        return file;
    }
}
