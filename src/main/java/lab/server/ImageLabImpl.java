package lab.server;

import java.awt.image.BufferedImage;
import java.rmi.RemoteException;
import java.util.List;
import lab.common.Bytes;
import lab.common.FilterSpec;
import lab.common.Histogram;
import lab.common.ImageLab;
import lab.common.ImageResult;
import lab.common.PaletteResult;
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
    public Histogram histogram(byte[] source) throws RemoteException {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        return ImageOps.histogram(SynthImage.fromPng(source));
    }

    @Override
    public PaletteResult quantize(byte[] source, int colors) throws RemoteException {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        long started = System.nanoTime();
        BufferedImage src = SynthImage.fromPng(source);
        ImageOps.Quantized q = ImageOps.quantize(src, colors);
        byte[] png = SynthImage.toPng(q.image());
        long computeMillis = (System.nanoTime() - started) / 1_000_000L;
        return new PaletteResult(png, q.palette(), q.image().getWidth(),
                q.image().getHeight(), computeMillis);
    }

    @Override
    public byte[] echo(byte[] payload) throws RemoteException {
        return payload;
    }
}
