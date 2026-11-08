package lab.common;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Deterministic test images. Reproducible benchmarks need reproducible inputs,
 * and the noise toggle changes how well the PNG compresses without changing
 * the pixel dimensions.
 */
public final class SynthImage {
    private SynthImage() {}

    public static BufferedImage generate(int size, boolean noise) {
        int n = Math.max(16, Math.min(8192, size));
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[n * n];
        Random rnd = new Random(42L);
        int span = Math.max(1, n - 1);
        for (int y = 0; y < n; y++) {
            int row = y * n;
            for (int x = 0; x < n; x++) {
                int r = x * 255 / span;
                int g = y * 255 / span;
                int b = 96 + (x * y) % 160;
                if (noise) {
                    r = (r + rnd.nextInt(96)) & 0xff;
                    g = (g + rnd.nextInt(96)) & 0xff;
                    b = (b + rnd.nextInt(96)) & 0xff;
                }
                px[row + x] = (r << 16) | (g << 8) | b;
            }
        }
        img.setRGB(0, 0, n, n, px, 0, n);
        return img;
    }

    public static byte[] toPng(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, "png", out)) {
                throw new IllegalStateException("no PNG writer available");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static BufferedImage fromPng(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                throw new IllegalArgumentException("payload is not a readable image");
            }
            return img;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
