package lab.tunnel;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.server.RMISocketFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One global factory, many transports.
 *
 * RMISocketFactory.setSocketFactory can be called exactly once per JVM, and a
 * client that wants to swap transports at runtime cannot unset it. So install
 * this wrapper once and retarget its delegate instead. Two things make that
 * work: RMI asks the factory for a socket whenever it needs a new connection,
 * and this class remembers every socket it handed out so closeAll() can drop
 * the live ones -- otherwise RMI keeps reusing the tunnel it already has and
 * the newly chosen transport is never dialled.
 */
public final class SwitchableSocketFactory extends RMISocketFactory {

    private static SwitchableSocketFactory installed;

    private volatile RMISocketFactory delegate;
    private final List<Socket> live = new CopyOnWriteArrayList<>();

    private SwitchableSocketFactory(RMISocketFactory delegate) {
        this.delegate = delegate;
    }

    /** Installs on first use and retargets afterwards. Never throws once installed. */
    public static synchronized SwitchableSocketFactory install(RMISocketFactory next) throws IOException {
        if (installed == null) {
            SwitchableSocketFactory factory = new SwitchableSocketFactory(orDefault(next));
            RMISocketFactory.setSocketFactory(factory);
            installed = factory;
            return factory;
        }
        installed.delegate = orDefault(next);
        return installed;
    }

    public static SwitchableSocketFactory installed() {
        return installed;
    }

    private static RMISocketFactory orDefault(RMISocketFactory next) {
        return next != null ? next : RMISocketFactory.getDefaultSocketFactory();
    }

    public void use(RMISocketFactory next) {
        delegate = orDefault(next);
    }

    public RMISocketFactory delegate() {
        return delegate;
    }

    public String describe() {
        RMISocketFactory d = delegate;
        return d instanceof TunnelSocketFactory tunnel ? tunnel.describe() : "direct";
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = delegate.createSocket(host, port);
        live.add(socket);
        return socket;
    }

    @Override
    public ServerSocket createServerSocket(int port) {
        throw new UnsupportedOperationException("SwitchableSocketFactory is client-side only");
    }

    /** Closes every socket this factory handed out; returns how many. */
    public int closeAll() {
        int n = live.size();
        for (Socket socket : live) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing is best effort; the point is to break RMI's cached connection
            }
        }
        live.clear();
        return n;
    }

    public int openSockets() {
        return live.size();
    }
}
