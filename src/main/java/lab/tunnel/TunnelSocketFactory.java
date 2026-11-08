package lab.tunnel;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.server.RMISocketFactory;

/**
 * Installed on the CLIENT JVM only. Every RMI connection, whatever host and
 * port the stub advertises, becomes a tunnel request to the relay.
 */
public final class TunnelSocketFactory extends RMISocketFactory {

    public enum Mode {
        CHUNKED,
        CONNECT
    }

    private final String relayHost;
    private final int relayPort;
    private final Mode mode;
    private final String chaos;

    public TunnelSocketFactory(String relayHost, int relayPort) {
        this(relayHost, relayPort, Mode.CHUNKED, "");
    }

    public TunnelSocketFactory(String relayHost, int relayPort, Mode mode, String chaos) {
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.mode = mode;
        this.chaos = chaos == null ? "" : chaos;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return mode == Mode.CONNECT
                ? TunnelClient.openConnect(relayHost, relayPort, host, port, chaos)
                : TunnelClient.openChunked(relayHost, relayPort, host, port, chaos);
    }

    @Override
    public ServerSocket createServerSocket(int port) {
        throw new UnsupportedOperationException("TunnelSocketFactory is client-side only");
    }

    public String relay() {
        return relayHost + ":" + relayPort;
    }

    public String describe() {
        return "tunnel/" + mode.name().toLowerCase() + " via " + relay()
                + (chaos.isBlank() ? "" : " [" + chaos + "]");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TunnelSocketFactory t
                && relayHost.equals(t.relayHost)
                && relayPort == t.relayPort
                && mode == t.mode
                && chaos.equals(t.chaos);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * relayHost.hashCode() + relayPort) + mode.hashCode() + chaos.hashCode();
    }
}
