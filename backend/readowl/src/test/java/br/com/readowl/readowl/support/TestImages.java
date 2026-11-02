package br.com.readowl.readowl.support;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

public final class TestImages {

    private TestImages() {
    }

    /**
     * Uma cor só: o arquivo é pequeno mesmo com muitos pixels.
     */
    public static byte[] png(int width, int height) {
        return write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY));
    }

    /**
     * Pixels aleatórios, que quase não comprimem: o arquivo fica perto de 3 bytes por pixel.
     */
    public static byte[] noisyPng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(1);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return write(image);
    }

    public static BufferedImage read(byte[] file) {
        try {
            return ImageIO.read(new ByteArrayInputStream(file));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] write(BufferedImage image) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bytes);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return bytes.toByteArray();
    }
}
