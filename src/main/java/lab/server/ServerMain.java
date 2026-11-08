package lab.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;

/**
 * Starts the RMI registry and the service.
 *
 * The important bit for the tunnel project: by default both listen on LOOPBACK
 * ONLY, so no other machine can reach them at all. The relay (which runs on this
 * same host) is the only way in. Nothing to configure in the firewall.
 *
 * -Dlab.bind=any switches to 0.0.0.0, which is what you need for baseline
 * direct measurements from another machine.
 */
public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        int registryPort = Integer.getInteger("lab.registryPort", 1099);
        int exportPort = Integer.getInteger("lab.exportPort", 1100);
        boolean bindAny = "any".equalsIgnoreCase(System.getProperty("lab.bind", "loopback"));
        String hostname = System.getProperty("lab.hostname", "127.0.0.1");

        System.setProperty("java.rmi.server.hostname", hostname);

        RMIServerSocketFactory ssf = bindAny
                ? null
                : port -> new ServerSocket(port, 64, InetAddress.getLoopbackAddress());

        ImageLabImpl impl = new ImageLabImpl();
        UnicastRemoteObject.exportObject(impl, exportPort, null, ssf);

        Registry registry = LocateRegistry.createRegistry(registryPort, null, ssf);
        registry.rebind("ImageLab", impl);

        String where = bindAny ? "0.0.0.0" : "127.0.0.1";
        System.out.println("ImageLab ready");
        System.out.println("  registry : " + where + ":" + registryPort);
        System.out.println("  export   : " + where + ":" + exportPort);
        System.out.println("  advertised: " + hostname + ":" + exportPort);
        System.out.println("  filters  : grayscale, posterize, sobel, blur, resize");
        System.out.flush();
    }

    @SuppressWarnings("unused")
    private static RMIServerSocketFactory loopback() {
        return port -> {
            try {
                return new ServerSocket(port, 64, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
