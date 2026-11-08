package lab.server;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import lab.common.FilterSpec;

/** The actual work. Not clever; it exists to generate CPU load and pixels. */
public final class ImageOps {
    private ImageOps() {}

    public static BufferedImage apply(BufferedImage src, FilterSpec spec) {
        return switch (spec.name()) {
            case "grayscale" -> grayscale(src);
            case "posterize" -> posterize(src, (int) spec.param("levels", 4));
            case "sobel" -> sobel(src);
            case "blur" -> blur(src, (int) spec.param("radius", 6));
            case "resize" -> resize(src, spec.param("scale", 0.5));
            default -> throw new IllegalArgumentException("unknown filter: " + spec.name());
        };
    }

    static BufferedImage grayscale(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int v = (((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587 + (c & 0xff) * 114) / 1000;
            px[i] = 0xff000000 | (v << 16) | (v << 8) | v;
        }
        return wrap(w, h, px);
    }

    static BufferedImage posterize(BufferedImage src, int levels) {
        int n = Math.max(2, Math.min(32, levels));
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = ((c >> 16) & 0xff) * n / 256 * (256 / n);
            int g = ((c >> 8) & 0xff) * n / 256 * (256 / n);
            int b = (c & 0xff) * n / 256 * (256 / n);
            px[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        return wrap(w, h, px);
    }

    static BufferedImage sobel(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        int[] lum = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            lum[i] = (((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587 + (c & 0xff) * 114) / 1000;
        }
        int[] out = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int gx = -at(lum, w, h, x - 1, y - 1) + at(lum, w, h, x + 1, y - 1)
                        - 2 * at(lum, w, h, x - 1, y) + 2 * at(lum, w, h, x + 1, y)
                        - at(lum, w, h, x - 1, y + 1) + at(lum, w, h, x + 1, y + 1);
                int gy = -at(lum, w, h, x - 1, y - 1) - 2 * at(lum, w, h, x, y - 1) - at(lum, w, h, x + 1, y - 1)
                        + at(lum, w, h, x - 1, y + 1) + 2 * at(lum, w, h, x, y + 1) + at(lum, w, h, x + 1, y + 1);
                int m = (int) Math.min(255, Math.sqrt((double) gx * gx + (double) gy * gy));
                out[y * w + x] = 0xff000000 | (m << 16) | (m << 8) | m;
            }
        }
        return wrap(w, h, out);
    }

    static BufferedImage blur(BufferedImage src, int radius) {
        int r = Math.max(1, Math.min(24, radius));
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);

        float sigma = Math.max(1f, r / 2f);
        float[] kernel = new float[2 * r + 1];
        float sum = 0f;
        for (int i = -r; i <= r; i++) {
            float v = (float) Math.exp(-(i * i) / (2.0 * sigma * sigma));
            kernel[i + r] = v;
            sum += v;
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= sum;
        }

        int[] pass1 = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float accR = 0f;
                float accG = 0f;
                float accB = 0f;
                for (int k = -r; k <= r; k++) {
                    int sx = Math.max(0, Math.min(w - 1, x + k));
                    int c = px[y * w + sx];
                    float wgt = kernel[k + r];
                    accR += ((c >> 16) & 0xff) * wgt;
                    accG += ((c >> 8) & 0xff) * wgt;
                    accB += (c & 0xff) * wgt;
                }
                pass1[y * w + x] = 0xff000000
                        | (clamp(accR) << 16) | (clamp(accG) << 8) | clamp(accB);
            }
        }

        int[] out = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float accR = 0f;
                float accG = 0f;
                float accB = 0f;
                for (int k = -r; k <= r; k++) {
                    int sy = Math.max(0, Math.min(h - 1, y + k));
                    int c = pass1[sy * w + x];
                    float wgt = kernel[k + r];
                    accR += ((c >> 16) & 0xff) * wgt;
                    accG += ((c >> 8) & 0xff) * wgt;
                    accB += (c & 0xff) * wgt;
                }
                out[y * w + x] = 0xff000000
                        | (clamp(accR) << 16) | (clamp(accG) << 8) | clamp(accB);
            }
        }
        return wrap(w, h, out);
    }

    static BufferedImage resize(BufferedImage src, double scale) {
        double s = Math.max(0.05, Math.min(4.0, scale));
        int w = Math.max(1, (int) Math.round(src.getWidth() * s));
        int h = Math.max(1, (int) Math.round(src.getHeight() * s));
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private static int at(int[] lum, int w, int h, int x, int y) {
        return lum[Math.max(0, Math.min(h - 1, y)) * w + Math.max(0, Math.min(w - 1, x))];
    }

    private static int clamp(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : Math.min(255, i);
    }

    private static BufferedImage wrap(int w, int h, int[] px) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        dst.setRGB(0, 0, w, h, px, 0, w);
        return dst;
    }
}
