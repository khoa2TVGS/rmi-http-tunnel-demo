package lab.server;

import java.awt.image.BufferedImage;
import java.rmi.RemoteException;
import java.util.List;
import lab.common.Bytes;
import lab.common.FilterSpec;
import lab.common.ImageLab;
import lab.common.ImageResult;
import lab.common.SynthImage;

public class ImageLabImpl implements ImageLab {

    private static final List<String> FILTERS =
            List.of("grayscale", "posterize", "sobel", "blur", "resize");

    @Override
    public String ping() throws RemoteException {
        return "ImageLab/" + System.getProperty("java.version");
    }

    @Override
    public List<String> filters() throws RemoteException {
        return FILTERS;
    }

    @Override
    public ImageResult transform(byte[] source, FilterSpec spec) throws RemoteException {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        String sourceSha = Bytes.sha256Hex(source);
        long started = System.nanoTime();
        BufferedImage src = SynthImage.fromPng(source);
        BufferedImage dst = ImageOps.apply(src, spec);
        byte[] png = SynthImage.toPng(dst);
        long computeMillis = (System.nanoTime() - started) / 1_000_000L;
        return new ImageResult(png, dst.getWidth(), dst.getHeight(),
                computeMillis, sourceSha, Bytes.sha256Hex(png));
    }

    @Override
    public int[] histogram(byte[] source) throws RemoteException {
        BufferedImage img = SynthImage.fromPng(source);
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[] hist = new int[256];
        for (int c : px) {
            int v = (((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587 + (c & 0xff) * 114) / 1000;
            hist[v]++;
        }
        return hist;
    }
}
