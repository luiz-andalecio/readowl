package br.com.readowl.readowl.support;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
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

    public static byte[] gif(int width, int height) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED), "gif", bytes);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return bytes.toByteArray();
    }

    /**
     * WebP sem perdas de uma cor só, montado à mão porque o Java não escreve WebP: cada canal tem um único símbolo, então os
     * pixels não ocupam nenhum bit, e o arquivo tem poucos bytes em qualquer tamanho.
     */
    public static byte[] webp(int width, int height) {
        BitSet bits = new BitSet();
        int[] position = {0};
        java.util.function.BiConsumer<Integer, Integer> write = (value, count) -> {
            for (int i = 0; i < count; i++) {
                bits.set(position[0]++, ((value >> i) & 1) == 1);
            }
        };
        write.accept(width - 1, 14);
        write.accept(height - 1, 14);
        write.accept(0, 1 + 3 + 1 + 1 + 1); // sem transparência, versão 0, sem transformações, sem cache e sem códigos por região
        for (int symbol : new int[]{200, 100, 50, 255, 0}) { // verde, vermelho, azul, transparência e distância
            write.accept(1, 1); // código simples
            write.accept(0, 1); // um símbolo
            write.accept(1, 1); // de 8 bits
            write.accept(symbol, 8);
        }
        byte[] stream = java.util.Arrays.copyOf(bits.toByteArray(), (position[0] + 7) / 8 + 8);
        int chunk = 1 + stream.length;
        int padded = chunk + chunk % 2;
        ByteBuffer file = ByteBuffer.allocate(8 + 4 + 8 + padded).order(ByteOrder.LITTLE_ENDIAN);
        file.put("RIFF".getBytes()).putInt(4 + 8 + padded).put("WEBP".getBytes()).put("VP8L".getBytes()).putInt(chunk);
        file.put((byte) 0x2F).put(stream);
        return file.array();
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
