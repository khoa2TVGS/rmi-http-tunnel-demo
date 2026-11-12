package lab.common;

import java.io.Serializable;

/**
 * One round trip's worth of numbers: 256 bins per channel plus the image size.
 * Small next to the image it describes, which is the point -- the tunnel carries
 * structured objects, not just blobs.
 */
public record Histogram(int width, int height, int[] red, int[] green, int[] blue, int[] luma)
        implements Serializable {

    public long pixels() {
        return (long) width * height;
    }

    public double meanLuma() {
        long sum = 0;
        for (int i = 0; i < luma.length; i++) {
            sum += (long) i * luma[i];
        }
        long n = pixels();
        return n == 0 ? 0 : (double) sum / n;
    }

    public int peakLuma() {
        int best = 0;
        for (int i = 1; i < luma.length; i++) {
            if (luma[i] > luma[best]) {
                best = i;
            }
        }
        return best;
    }
}
