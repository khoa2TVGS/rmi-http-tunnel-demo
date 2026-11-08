package lab.tunnel;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.server.RMISocketFactory;

/**
 * Installed on the CLIENT JVM only. Every RMI connection, whatever host and
 * port the stub advertises, becomes a tunnel request to the local relay.
 */
public final class TunnelSocketFactory extends RMISocketFactory {
    private final String relayHost;
    private final int relayPort;

    public TunnelSocketFactory(String relayHost, int relayPort) {
        this.relayHost = relayHost;
        this.relayPort = relayPort;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return TunnelClient.open(relayHost, relayPort, host, port);
    }

    @Override
    public ServerSocket createServerSocket(int port) {
        throw new UnsupportedOperationException("TunnelSocketFactory is client-side only");
    }

    public String relay() {
        return relayHost + ":" + relayPort;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TunnelSocketFactory t
                && relayHost.equals(t.relayHost)
                && relayPort == t.relayPort;
    }

    @Override
    public int hashCode() {
        return 31 * relayHost.hashCode() + relayPort;
    }
}
