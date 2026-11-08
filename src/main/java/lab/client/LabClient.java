package lab.client;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMISocketFactory;
import lab.common.ImageLab;
import lab.tunnel.SwitchableSocketFactory;

/**
 * Single place where a client obtains the remote service.
 *
 * The factory argument is the seam for the tunnel: null for a direct
 * connection, a TunnelSocketFactory for the relayed one. RMI latches its
 * global factory on first use, so a SwitchableSocketFactory is installed here
 * once and every later connect retargets it -- that is what makes the UI's
 * Disconnect button possible without restarting the JVM.
 *
 * Connecting retries. Closing a connection does not make RMI forget it
 * instantly: its reader thread retires the connection a few ms later, and
 * until then a call can be handed the dead one and fail with "Socket closed".
 * Both endpoints are used here (registry and object), so a swap can hit this
 * twice in a row. Each failed attempt does kill the connection it touched,
 * and the warm-up ping proves the object endpoint too, so a successful
 * connect() means the whole path really works.
 */
public final class LabClient {

    private static final int ATTEMPTS = 4;
    private static final long PAUSE_MS = 40;

    private LabClient() {}

    /** Connects through the given factory (null = direct) and proves both endpoints answer. */
    public static ImageLab connect(String host, int port, RMISocketFactory factory) throws Exception {
        SwitchableSocketFactory gate = SwitchableSocketFactory.install(factory);
        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            gate.closeAll();
            try {
                Registry registry = LocateRegistry.getRegistry(host, port);
                ImageLab lab = (ImageLab) registry.lookup("ImageLab");
                lab.ping();
                return lab;
            } catch (Exception e) {
                last = e;
                if (attempt < ATTEMPTS) {
                    Thread.sleep(PAUSE_MS);
                }
            }
        }
        throw last;
    }

    /** Closes the sockets this JVM opened. The next connect may use a different factory. */
    public static int disconnect() {
        SwitchableSocketFactory gate = SwitchableSocketFactory.installed();
        return gate == null ? 0 : gate.closeAll();
    }

    /** "direct", or "tunnel/chunked via 127.0.0.1:8080". */
    public static String describe() {
        SwitchableSocketFactory gate = SwitchableSocketFactory.installed();
        return gate == null ? "direct" : gate.describe();
    }
}
