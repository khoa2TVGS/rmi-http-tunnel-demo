package lab.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/** The remote service exposed over RMI (and later, over the HTTP tunnel). */
public interface ImageLab extends Remote {

    String ping() throws RemoteException;

    List<String> filters() throws RemoteException;

    ImageResult transform(byte[] source, FilterSpec spec) throws RemoteException;

    /** Deliberately tiny reply, for comparing against the megabyte payloads. */
    int[] histogram(byte[] source) throws RemoteException;
}
