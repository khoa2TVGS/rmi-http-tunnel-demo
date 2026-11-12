package lab.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface ImageLab extends Remote {

    String ping() throws RemoteException;

    List<String> filters() throws RemoteException;

    /** Filters, plus the geometry ops: rotate, flip, crop. */
    ImageResult transform(byte[] source, FilterSpec spec) throws RemoteException;

    /** Per-channel and luma histograms of the source image. */
    Histogram histogram(byte[] source) throws RemoteException;

    /** Reduce the image to at most {@code colors} colours (deterministic k-means). */
    PaletteResult quantize(byte[] source, int colors) throws RemoteException;

    /**
     * Returns the payload unchanged. No compute, no image decoding: whatever
     * time this takes is transport plus serialisation, which is exactly what a
     * transport benchmark needs to isolate.
     */
    byte[] echo(byte[] payload) throws RemoteException;
}
