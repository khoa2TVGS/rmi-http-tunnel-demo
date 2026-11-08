package lab.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * A Socket whose bytes travel inside an HTTP/1.1 chunked exchange.
 *
 * RMI asks for a Socket and gets an object with the same shape; only the
 * framing differs. Everything RMI actually touches is delegated to the real
 * TCP connection underneath.
 */
public final class TunnelSocket extends Socket {
    private final Socket raw;
    private final InputStream in;
    private final OutputStream out;

    TunnelSocket(Socket raw) throws IOException {
        this.raw = raw;
        this.in = new ChunkedInputStream(raw.getInputStream());
        this.out = new ChunkedOutputStream(raw.getOutputStream());
    }

    @Override
    public InputStream getInputStream() {
        return in;
    }

    @Override
    public OutputStream getOutputStream() {
        return out;
    }

    @Override
    public void close() throws IOException {
        raw.close();
    }

    @Override
    public boolean isClosed() {
        return raw.isClosed();
    }

    @Override
    public boolean isConnected() {
        return raw.isConnected();
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        raw.setSoTimeout(timeout);
    }

    @Override
    public int getSoTimeout() throws SocketException {
        return raw.getSoTimeout();
    }

    @Override
    public void setTcpNoDelay(boolean on) throws SocketException {
        raw.setTcpNoDelay(on);
    }

    @Override
    public boolean getTcpNoDelay() throws SocketException {
        return raw.getTcpNoDelay();
    }

    @Override
    public InetAddress getInetAddress() {
        return raw.getInetAddress();
    }

    @Override
    public int getPort() {
        return raw.getPort();
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        return raw.getRemoteSocketAddress();
    }

    @Override
    public String toString() {
        return "TunnelSocket[" + raw.getRemoteSocketAddress() + "]";
    }
}
