package br.com.readowl.readowl.media.application;

import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.support.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarImagesTest {

    public static byte[] image(String format, int width, int height) {
        BufferedImage image = new BufferedImage(width, height,
                "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width / 2, height);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, bytes);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return bytes.toByteArray();
    }

    private static BufferedImage read(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static void assertRefused(byte[] file, String code) {
        assertThatThrownBy(() -> AvatarImages.normalize(file))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg"})
    void anAllowedImageBecomesAJpegWithTheSameSize(String format) throws IOException {
        byte[] result = AvatarImages.normalize(image(format, 300, 300));

        assertThat(result[0] & 0xFF).isEqualTo(0xFF);
        assertThat(result[1] & 0xFF).isEqualTo(0xD8);
        assertThat(read(result).getWidth()).isEqualTo(300);
        assertThat(read(result).getHeight()).isEqualTo(300);
    }

    @Test
    void smallerAndNonSquareImagesAreAccepted() throws IOException {
        assertThat(read(AvatarImages.normalize(image("png", 80, 40))).getWidth()).isEqualTo(80);
    }

    @Test
    void transparencyBecomesWhite() throws IOException {
        BufferedImage result = read(AvatarImages.normalize(image("png", 100, 100)));

        assertThat(new Color(result.getRGB(90, 50))).extracting(Color::getRed).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.INTEGER)
                .isGreaterThan(240);
    }

    @ParameterizedTest
    @ValueSource(strings = {"301x100", "100x301", "5000x5000"})
    void anImageAboveTheLimitIsRefused(String size) {
        String[] sides = size.split("x");

        assertRefused(image("png", Integer.parseInt(sides[0]), Integer.parseInt(sides[1])), "AVATAR_TOO_LARGE");
    }

    @Test
    void metadataAndAnythingHiddenInTheFileAreDiscarded() {
        byte[] original = image("jpeg", 50, 50);
        byte[] marker = "SEGREDO-LOCALIZACAO".getBytes(StandardCharsets.US_ASCII);
        // Um comentário JPEG logo depois do início do arquivo, como os metadados de uma câmera.
        ByteArrayOutputStream withComment = new ByteArrayOutputStream();
        withComment.write(original, 0, 2);
        withComment.write(0xFF);
        withComment.write(0xFE);
        withComment.write(0);
        withComment.write(marker.length + 2);
        withComment.writeBytes(marker);
        withComment.write(original, 2, original.length - 2);
        byte[] file = withComment.toByteArray();
        assertThat(new String(file, StandardCharsets.ISO_8859_1)).contains("SEGREDO-LOCALIZACAO");

        byte[] result = AvatarImages.normalize(file);

        assertThat(new String(result, StandardCharsets.ISO_8859_1)).doesNotContain("SEGREDO-LOCALIZACAO");
    }

    @Test
    void otherFormatsAndForgedContentAreRefused() {
        assertRefused("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8), "INVALID_IMAGE");
        assertRefused("GIF89a qualquer coisa".getBytes(StandardCharsets.US_ASCII), "INVALID_IMAGE");
        assertRefused(new byte[0], "INVALID_IMAGE");
        assertRefused(image("bmp", 10, 10), "INVALID_IMAGE");
    }

    @Test
    void aGifAndAWebpBecomeJpeg() throws IOException {
        assertThat(read(AvatarImages.normalize(image("gif", 10, 10))).getWidth()).isEqualTo(10);
        assertThat(read(AvatarImages.normalize(TestImages.webp(10, 10))).getWidth()).isEqualTo(10);
        assertThat(AvatarImages.normalize(TestImages.webp(10, 10))).startsWith((byte) 0xFF, (byte) 0xD8);
    }

    @Test
    void aFileThatOnlyStartsLikeAnImageIsRefused() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 'x', 'y', 'z'};
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2, 3};

        assertRefused(png, "INVALID_IMAGE");
        assertRefused(jpeg, "INVALID_IMAGE");
    }

    @Test
    void aTruncatedImageIsRefused() {
        byte[] whole = image("png", 200, 200);
        byte[] cut = java.util.Arrays.copyOf(whole, whole.length / 2);

        assertRefused(cut, "INVALID_IMAGE");
    }
}
