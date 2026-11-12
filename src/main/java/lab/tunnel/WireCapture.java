package lab.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Records the actual bytes of one tunnel handshake, so the report (and the UI's
 * tunnel inspector) can show the framing instead of describing it.
 *
 * The point being demonstrated is the order: the relay answers 200 while the
 * request body is still empty, and only then does either side send a byte of
 * application data.
 */
public final class WireCapture {
    private WireCapture() {}

    public static void main(String[] args) throws Exception {
        String relayHost = args.length > 0 ? args[0] : "127.0.0.1";
        int relayPort = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        System.out.print(capture(relayHost, relayPort, ""));
    }

    /** @return the transcript of one handshake through the relay, CR/LF made visible. */
    public static String capture(String relayHost, int relayPort, String chaos) throws IOException {
        StringBuilder log = new StringBuilder();
        ServerSocket echo = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread target = new Thread(() -> {
            try (Socket s = echo.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // the capture is over
            }
        }, "echo");
        target.setDaemon(true);
        target.start();
        log.append("echo target  : 127.0.0.1:").append(echo.getLocalPort()).append('\n');
        log.append("relay        : ").append(relayHost).append(':').append(relayPort).append('\n');
        if (chaos != null && !chaos.isBlank()) {
            log.append("chaos        : ").append(chaos).append('\n');
        }
        log.append('\n');

        try (Socket raw = new Socket(relayHost, relayPort)) {
            raw.setTcpNoDelay(true);
            InputStream in = raw.getInputStream();
            OutputStream out = raw.getOutputStream();

            String head = "POST /tunnel?target=127.0.0.1:" + echo.getLocalPort() + " HTTP/1.1\r\n"
                    + "Host: " + relayHost + ":" + relayPort + "\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + (chaos == null || chaos.isBlank() ? "" : "X-Tunnel-Chaos: " + chaos + "\r\n")
                    + "\r\n";
            log.append("client sends (request head, no body yet):\n");
            show(log, head);
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            log.append('\n').append("relay answers, before any body byte arrived:\n");
            show(log, readHead(in));

            String body = "hello, tunnel";
            String frame = Integer.toHexString(body.length()) + "\r\n" + body + "\r\n";
            log.append('\n').append("client sends 13 bytes of application data:\n");
            show(log, frame);
            out.write(frame.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            raw.setSoTimeout(600);
            StringBuilder echoed = new StringBuilder();
            byte[] back = new byte[128];
            try {
                int n;
                while ((n = in.read(back)) > 0) {
                    echoed.append(new String(back, 0, n, StandardCharsets.ISO_8859_1));
                }
            } catch (SocketTimeoutException end) {
                // nothing more is coming
            }
            log.append('\n').append("relay sends the echo back, chunk framed:\n");
            show(log, echoed.toString());
        } finally {
            echo.close();
        }
        return log.toString();
    }

    /** Renders CR/LF visibly; control bytes would otherwise be invisible in a terminal. */
    private static void show(StringBuilder into, String text) {
        for (String line : text.replace("\r\n", "\\r\\n\n").split("\n")) {
            if (!line.isEmpty()) {
                into.append("  | ").append(line).append('\n');
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (!sb.toString().endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("relay closed before answering");
            }
            sb.append((char) c);
        }
        return sb.toString();
    }
}
