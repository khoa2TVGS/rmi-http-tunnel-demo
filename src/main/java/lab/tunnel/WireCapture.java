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
 * Prints the actual bytes of one tunnel handshake, so the report can show the
 * framing instead of describing it.
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
        System.out.println("echo target  : 127.0.0.1:" + echo.getLocalPort());
        System.out.println();

        try (Socket raw = new Socket(relayHost, relayPort)) {
            raw.setTcpNoDelay(true);
            InputStream in = raw.getInputStream();
            OutputStream out = raw.getOutputStream();

            String head = "POST /tunnel?target=127.0.0.1:" + echo.getLocalPort() + " HTTP/1.1\r\n"
                    + "Host: " + relayHost + ":" + relayPort + "\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "\r\n";
            System.out.println("client sends (request head, no body yet):");
            show(head);
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            String response = readHead(in);
            System.out.println();
            System.out.println("relay answers, before any body byte arrived:");
            show(response);

            String body = "hello, tunnel";
            String frame = Integer.toHexString(body.length()) + "\r\n" + body + "\r\n";
            System.out.println();
            System.out.println("client sends 13 bytes of application data:");
            show(frame);
            out.write(frame.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            raw.setSoTimeout(400);
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
            System.out.println();
            System.out.println("relay sends the echo back, chunk framed:");
            show(echoed.toString());
        }
    }

    /** Renders CR/LF visibly; control bytes would otherwise be invisible in a terminal. */
    private static void show(String text) {
        for (String line : text.replace("\r\n", "\\r\\n\n").split("\n")) {
            if (!line.isEmpty()) {
                System.out.println("  | " + line);
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
