package lab.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface ImageLab extends Remote {

    String ping() throws RemoteException;

    List<String> filters() throws RemoteException;

    ImageResult transform(byte[] source, FilterSpec spec) throws RemoteException;

    int[] histogram(byte[] source) throws RemoteException;

    /**
     * Returns the payload unchanged. No compute, no image decoding: whatever
     * time this takes is transport plus serialisation, which is exactly what a
     * transport benchmark needs to isolate.
     */
    byte[] echo(byte[] payload) throws RemoteException;
}
