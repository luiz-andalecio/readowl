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
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Valida e refaz as imagens guardadas no armazenamento. O formato vem dos próprios bytes, e não do que o navegador ou o site
 * de origem declaram. O tamanho é lido antes de decodificar, o que barra imagens que ocupam gigabytes depois de abertas. Refazer
 * a imagem descarta metadados, como o local da foto, e qualquer conteúdo escondido no arquivo.
 */
final class ImageFiles {

    static final String CONTENT_TYPE = "image/jpeg";

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final float JPEG_QUALITY = 0.9f;

    private ImageFiles() {
    }

    record Size(int width, int height) {
    }

    /**
     * @param check recusa a imagem pelo tamanho, antes de decodificar
     * @param limit o maior tamanho guardado. Acima dele, a imagem é reduzida, mantendo a proporção
     */
    static byte[] normalize(byte[] file, Consumer<Size> check, Size limit) {
        String format = formatOf(file);
        ImageReader reader = ImageIO.getImageReadersByFormatName(format).next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(input, true, true);
            Size size = new Size(reader.getWidth(0), reader.getHeight(0));
            check.accept(size);
            ImageReadParam parameters = reader.getDefaultReadParam();
            // Pula linhas e colunas ao decodificar, sem ficar menor que o limite: a memória usada acompanha o que será guardado.
            int skip = Math.max(1, Math.min(size.width() / limit.width(), size.height() / limit.height()));
            parameters.setSourceSubsampling(skip, skip, 0, 0);
            return encode(flatten(reader.read(0, parameters), limit));
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
    private static BufferedImage flatten(BufferedImage source, Size limit) {
        double scale = Math.min(1, Math.min((double) limit.width() / source.getWidth(), (double) limit.height() / source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage flat = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = flat.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
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
