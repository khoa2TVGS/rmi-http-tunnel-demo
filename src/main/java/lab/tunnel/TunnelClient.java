package lab.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Client half of the tunnel.
 *
 * Chunked mode: sends the POST head, waits for the relay's 200, then frames
 * everything as HTTP chunks. The response head is read one byte at a time on
 * purpose — any buffered reader here would swallow the first bytes of the body,
 * which are JRMP and cannot be un-read.
 *
 * CONNECT mode: sends "CONNECT host:port", waits for "200 Connection
 * Established", then returns the bare socket. Roughly forty lines, but some
 * proxies refuse CONNECT, which is why chunked exists at all.
 */
public final class TunnelClient {
    private TunnelClient() {}

    public static TunnelSocket openChunked(String relayHost, int relayPort, String targetHost, int targetPort,
            String chaos) throws IOException {
        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        raw.connect(new InetSocketAddress(relayHost, relayPort), 10_000);

        String target = targetHost + ":" + targetPort;
        StringBuilder head = new StringBuilder()
                .append("POST /tunnel?target=")
                .append(URLEncoder.encode(target, StandardCharsets.UTF_8))
                .append(" HTTP/1.1\r\n")
                .append("Host: ").append(relayHost).append(':').append(relayPort).append("\r\n")
                .append("Transfer-Encoding: chunked\r\n");
        if (chaos != null && !chaos.isBlank()) {
            head.append("X-Tunnel-Chaos: ").append(chaos).append("\r\n");
        }
        head.append("\r\n");

        OutputStream out = raw.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String responseHead = readHead(raw.getInputStream());
        String status = responseHead.substring(0, responseHead.indexOf("\r\n"));
        if (!status.contains(" 200")) {
            raw.close();
            throw new IOException("relay refused tunnel: " + status);
        }
        if (!responseHead.toLowerCase().contains("transfer-encoding: chunked")) {
            raw.close();
            throw new IOException("relay did not answer with chunked encoding");
        }
        return new TunnelSocket(raw);
    }

    public static Socket openConnect(String relayHost, int relayPort, String targetHost, int targetPort,
            String chaos) throws IOException {
        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        raw.connect(new InetSocketAddress(relayHost, relayPort), 10_000);

        StringBuilder head = new StringBuilder()
                .append("CONNECT ").append(targetHost).append(':').append(targetPort).append(" HTTP/1.1\r\n")
                .append("Host: ").append(relayHost).append(':').append(relayPort).append("\r\n");
        if (chaos != null && !chaos.isBlank()) {
            head.append("X-Tunnel-Chaos: ").append(chaos).append("\r\n");
        }
        head.append("\r\n");

        OutputStream out = raw.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String responseHead = readHead(raw.getInputStream());
        String status = responseHead.substring(0, responseHead.indexOf("\r\n"));
        if (!status.contains(" 200")) {
            raw.close();
            throw new IOException("relay refused CONNECT: " + status);
        }
        return raw;
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (!sb.toString().endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("relay closed while sending the response head");
            }
            sb.append((char) c);
            if (sb.length() > 8192) {
                throw new IOException("response head too long");
            }
        }
        return sb.toString();
    }
}
