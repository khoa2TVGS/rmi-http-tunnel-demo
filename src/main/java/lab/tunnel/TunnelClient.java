package lab.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Client half of the tunnel: sends the POST head, waits for the relay's 200,
 * then hands back a socket that frames everything as chunked.
 *
 * The response head is read one byte at a time on purpose. Any buffered reader
 * here would swallow the first bytes of the body, which are JRMP and cannot be
 * un-read.
 */
public final class TunnelClient {
    private TunnelClient() {}

    public static TunnelSocket open(String relayHost, int relayPort, String targetHost, int targetPort)
            throws IOException {
        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        raw.connect(new InetSocketAddress(relayHost, relayPort), 10_000);

        String target = targetHost + ":" + targetPort;
        String head = "POST /tunnel?target="
                + URLEncoder.encode(target, StandardCharsets.UTF_8)
                + " HTTP/1.1\r\n"
                + "Host: " + relayHost + ":" + relayPort + "\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n";

        OutputStream out = raw.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
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
