package lab.client;

import java.io.IOException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMISocketFactory;
import lab.common.ImageLab;

/**
 * Single place where a client obtains the remote service.
 *
 * The factory argument is the seam for the tunnel: null for a direct
 * connection, a TunnelSocketFactory for the relayed one. RMI latches the
 * global factory on first use, so it must be installed here, before the
 * first lookup, and it cannot be swapped afterwards.
 */
public final class LabClient {
    private static RMISocketFactory installed;

    private LabClient() {}

    public static ImageLab connect(String host, int port, RMISocketFactory factory) throws Exception {
        if (factory != null) {
            synchronized (LabClient.class) {
                if (installed == null) {
                    RMISocketFactory.setSocketFactory(factory);
                    installed = factory;
                } else if (!installed.equals(factory)) {
                    throw new IOException(
                            "a different RMI socket factory is already installed in this JVM; restart to switch transport");
                }
            }
        }
        Registry registry = LocateRegistry.getRegistry(host, port);
        return (ImageLab) registry.lookup("ImageLab");
    }
}
