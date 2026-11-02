package br.com.readowl.readowl.media.application;

import br.com.readowl.readowl.shared.exception.ApiException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Valida e refaz a foto de perfil. O formato vem dos próprios bytes, e não do que o navegador declara. O tamanho é lido
 * antes de decodificar, o que barra imagens que ocupam gigabytes depois de abertas. Refazer a imagem descarta metadados,
 * como o local da foto, e qualquer conteúdo escondido no arquivo.
 */
public final class AvatarImages {

    public static final int MAX_SIDE = 300;
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final String CONTENT_TYPE = "image/jpeg";

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final float JPEG_QUALITY = 0.9f;

    private AvatarImages() {
    }

    public static byte[] normalize(byte[] file) {
        String format = formatOf(file);
        ImageReader reader = ImageIO.getImageReadersByFormatName(format).next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width > MAX_SIDE || height > MAX_SIDE) {
                throw ApiException.badRequest("AVATAR_TOO_LARGE", "A foto pode ter no máximo " + MAX_SIDE + "x" + MAX_SIDE + " pixels.");
            }
            ImageReadParam parameters = reader.getDefaultReadParam();
            return encode(flatten(reader.read(0, parameters)));
        } catch (IOException | IllegalArgumentException | IllegalStateException | ArrayIndexOutOfBoundsException
                 | NegativeArraySizeException ex) {
            throw invalid();
        } finally {
            reader.dispose();
        }
    }

    private static String formatOf(byte[] file) {
        if (startsWith(file, JPEG)) {
            return "jpeg";
        }
        if (startsWith(file, PNG)) {
            return "png";
        }
        throw invalid();
    }

    private static boolean startsWith(byte[] file, byte[] prefix) {
        return file.length > prefix.length && Arrays.equals(file, 0, prefix.length, prefix, 0, prefix.length);
    }

    /**
     * JPEG não tem transparência: ela vira fundo branco.
     */
    private static BufferedImage flatten(BufferedImage source) {
        BufferedImage flat = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = flat.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, flat.getWidth(), flat.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return flat;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static ApiException invalid() {
        return ApiException.badRequest("INVALID_IMAGE", "Envie uma imagem JPEG ou PNG válida.");
    }
}
