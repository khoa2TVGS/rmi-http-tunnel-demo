package lab.server;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import lab.common.FilterSpec;
import lab.common.Histogram;

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
            case "rotate" -> rotate(src, (int) spec.param("degrees", 90));
            case "flip" -> flip(src, spec.param("horizontal", 1) > 0.5);
            case "crop" -> crop(src, spec.param("margin", 0.1));
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

    static BufferedImage rotate(BufferedImage src, int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        if (d == 0) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean quarter = d == 90 || d == 270;
        int dw = quarter ? h : w;
        int dh = quarter ? w : h;
        BufferedImage dst = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.translate(dw / 2.0, dh / 2.0);
        g.rotate(Math.toRadians(d));
        g.translate(-w / 2.0, -h / 2.0);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    static BufferedImage flip(BufferedImage src, boolean horizontal) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        if (horizontal) {
            g.translate(w, 0);
            g.scale(-1, 1);
        } else {
            g.translate(0, h);
            g.scale(1, -1);
        }
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    /** Trims the same fraction off every side; margin is clamped to 45% a side. */
    static BufferedImage crop(BufferedImage src, double margin) {
        double m = Math.max(0, Math.min(0.45, margin));
        int w = src.getWidth();
        int h = src.getHeight();
        int x = (int) Math.round(w * m);
        int y = (int) Math.round(h * m);
        int cw = Math.max(1, w - 2 * x);
        int ch = Math.max(1, h - 2 * y);
        BufferedImage dst = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, x, y, x + cw, y + ch, 0, 0, cw, ch, null);
        g.dispose();
        return dst;
    }

    static Histogram histogram(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        int[] red = new int[256];
        int[] green = new int[256];
        int[] blue = new int[256];
        int[] luma = new int[256];
        for (int c : px) {
            int r = (c >> 16) & 0xff;
            int g = (c >> 8) & 0xff;
            int b = c & 0xff;
            red[r]++;
            green[g]++;
            blue[b]++;
            luma[(r * 299 + g * 587 + b * 114) / 1000]++;
        }
        return new Histogram(w, h, red, green, blue, luma);
    }

    /** The reduced image plus the colours it was reduced to. */
    public record Quantized(BufferedImage image, int[] palette) {}

    static Quantized quantize(BufferedImage src, int colors) {
        int k = Math.max(2, Math.min(64, colors));
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        int[] palette = fitPalette(px, k);
        for (int i = 0; i < px.length; i++) {
            px[i] = 0xff000000 | palette[nearest(px[i], palette)];
        }
        return new Quantized(wrap(w, h, px), palette);
    }

    /**
     * Deterministic k-means, then duplicates dropped. Deterministic matters:
     * two runs on the same input must produce the same palette or the demo is
     * not repeatable. Centroids are seeded by even stride through the pixels
     * rather than randomly, and only a bounded sample is used for fitting.
     */
    static int[] fitPalette(int[] px, int k) {
        int[] cen = new int[k];
        for (int i = 0; i < k; i++) {
            cen[i] = px[(int) ((long) px.length * i / k)];
        }
        long[] sr = new long[k];
        long[] sg = new long[k];
        long[] sb = new long[k];
        int[] count = new int[k];
        int step = Math.max(1, px.length / 200_000);
        for (int iter = 0; iter < 8; iter++) {
            java.util.Arrays.fill(sr, 0);
            java.util.Arrays.fill(sg, 0);
            java.util.Arrays.fill(sb, 0);
            java.util.Arrays.fill(count, 0);
            for (int i = 0; i < px.length; i += step) {
                int c = px[i];
                int best = nearest(c, cen);
                sr[best] += (c >> 16) & 0xff;
                sg[best] += (c >> 8) & 0xff;
                sb[best] += c & 0xff;
                count[best]++;
            }
            for (int i = 0; i < k; i++) {
                if (count[i] > 0) {
                    cen[i] = 0xff000000
                            | ((int) (sr[i] / count[i]) << 16)
                            | ((int) (sg[i] / count[i]) << 8)
                            | (int) (sb[i] / count[i]);
                }
            }
        }
        java.util.LinkedHashSet<Integer> unique = new java.util.LinkedHashSet<>();
        for (int c : cen) {
            unique.add(c);
        }
        int[] out = new int[unique.size()];
        int i = 0;
        for (int c : unique) {
            out[i++] = c;
        }
        return out;
    }

    private static int nearest(int c, int[] palette) {
        int r = (c >> 16) & 0xff;
        int g = (c >> 8) & 0xff;
        int b = c & 0xff;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int p = palette[i];
            int dr = r - ((p >> 16) & 0xff);
            int dg = g - ((p >> 8) & 0xff);
            int db = b - (p & 0xff);
            int d = dr * dr + dg * dg + db * db;
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
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
