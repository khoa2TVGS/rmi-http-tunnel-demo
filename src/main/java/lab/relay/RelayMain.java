package lab.relay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lab.common.Bytes;
import lab.tunnel.ChunkedInputStream;
import lab.tunnel.ChunkedOutputStream;

/**
 * Relay: reads one HTTP request head, answers 200 before any body byte has
 * arrived, then splices the body to a raw TCP connection to the RMI endpoint.
 *
 * Two framings are accepted:
 *   POST /tunnel?target=host:port   -> chunked both ways
 *   CONNECT host:port               -> raw pipe
 *
 * The head is written by hand for one reason: the 200 must reach the client
 * while the request body is still empty, because the client will not send a
 * single body byte until it sees that 200. Anything that buffers the head
 * (com.sun.net.httpserver does) turns this into a deadlock.
 */
public final class RelayMain {
    private static final int MAX_HEAD = 8192;
    private static final byte[] CHUNKED_HEAD =
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CONNECT_HEAD =
            "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private RelayMain() {}

    public static void main(String[] args) throws Exception {
        int port = 8080;
        Set<String> allow = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--allow" -> {
                    for (String s : args[++i].split(",")) {
                        if (!s.isBlank()) {
                            allow.add(s.trim());
                        }
                    }
                }
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }

        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port), 50);
        System.out.println("relay        : listening on 0.0.0.0:" + port);
        System.out.println("allowlist    : " + (allow.isEmpty() ? "loopback only" : allow));
        System.out.flush();

        while (true) {
            Socket client = server.accept();
            Thread t = new Thread(() -> serve(client, allow), "relay-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void serve(Socket client, Set<String> allow) {
        long started = System.currentTimeMillis();
        String target = "?";
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String head = readHead(in);
            int eol = head.indexOf("\r\n");
            String requestLine = eol < 0 ? head.trim() : head.substring(0, eol);

            boolean connectMode = requestLine.startsWith("CONNECT ");
            if (connectMode) {
                int lastSpace = requestLine.lastIndexOf(' ');
                target = requestLine.substring("CONNECT ".length(), lastSpace).trim();
            } else if (requestLine.startsWith("POST /tunnel?")) {
                target = param(requestLine, "target");
            } else {
                reply(out, 200, "rmi-tunnel-lab relay\nPOST /tunnel?target=host:port\nCONNECT host:port\n");
                closeQuietly(client);
                return;
            }

            if (target == null) {
                reply(out, 400, "missing target\n");
                closeQuietly(client);
                return;
            }
            if (!allowed(target, allow)) {
                reply(out, 403, "target not allowed: " + target + "\n");
                closeQuietly(client);
                return;
            }
            int colon = target.lastIndexOf(':');
            if (colon <= 0) {
                reply(out, 400, "target must be host:port\n");
                closeQuietly(client);
                return;
            }

            Chaos chaos = Chaos.parse(header(head, "X-Tunnel-Chaos"));

            Socket upstream = new Socket();
            upstream.connect(new InetSocketAddress(target.substring(0, colon),
                    Integer.parseInt(target.substring(colon + 1))), 5000);
            upstream.setTcpNoDelay(true);

            // The head goes out now, with no body byte behind it.
            out.write(connectMode ? CONNECT_HEAD : CHUNKED_HEAD);
            out.flush();

            System.out.println("tunnel up    : " + (connectMode ? "CONNECT" : "chunked") + " " + target
                    + "  <- " + client.getRemoteSocketAddress()
                    + (chaos.isNone() ? "" : "  [" + chaos + "]"));
            System.out.flush();

            InputStream fromClient = connectMode ? in : new ChunkedInputStream(in);
            OutputStream toClient = connectMode ? out : new ChunkedOutputStream(out);
            InputStream fromUpstream = upstream.getInputStream();
            OutputStream toUpstream = upstream.getOutputStream();

            AtomicLong downstream = new AtomicLong();
            Thread down = new Thread(() -> {
                downstream.set(pump(fromUpstream, toClient, chaos));
                closeQuietly(upstream);
                closeQuietly(toClient);
                closeQuietly(client);
            }, "relay-down");
            down.setDaemon(true);
            down.start();

            long upstreamBytes = pump(fromClient, toUpstream, Chaos.NONE);
            closeQuietly(upstream);
            closeQuietly(toClient);
            closeQuietly(client);
            down.join(3000);

            System.out.printf("tunnel done  : up %s, down %s, %d ms%n",
                    Bytes.human(upstreamBytes), Bytes.human(downstream.get()),
                    System.currentTimeMillis() - started);
            System.out.flush();
        } catch (Exception e) {
            System.out.println("tunnel err   : " + target + " " + e);
            closeQuietly(client);
        }
    }

    /** Copies bytes, applying the chaos knobs. Returns bytes actually forwarded. */
    private static long pump(InputStream in, OutputStream out, Chaos chaos) {
        byte[] buf = new byte[32 * 1024];
        int chunk = chaos.buffer();
        byte[] acc = chunk > 0 ? new byte[chunk] : null;
        int accLen = 0;
        long total = 0L;
        long toSkip = chaos.drop();
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                int off = 0;
                if (toSkip > 0) {
                    int take = (int) Math.min(toSkip, n);
                    toSkip -= take;
                    off = take;
                    if (off == n) {
                        continue;
                    }
                }
                if (acc == null) {
                    out.write(buf, off, n - off);
                    out.flush();
                    total += n - off;
                } else {
                    int p = off;
                    while (p < n) {
                        int take = Math.min(chunk - accLen, n - p);
                        System.arraycopy(buf, p, acc, accLen, take);
                        accLen += take;
                        p += take;
                        if (accLen == chunk) {
                            out.write(acc, 0, accLen);
                            out.flush();
                            total += accLen;
                            accLen = 0;
                        }
                    }
                }
                if (chaos.delay() > 0) {
                    Thread.sleep(chaos.delay());
                }
            }
            if (accLen > 0) {
                out.write(acc, 0, accLen);
                out.flush();
                total += accLen;
            }
        } catch (IOException | InterruptedException ignored) {
            // either side went away; the opposite pump will notice
        }
        return total;
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (!sb.toString().endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("client closed during request head");
            }
            sb.append((char) c);
            if (sb.length() > MAX_HEAD) {
                throw new IOException("request head too long");
            }
        }
        return sb.toString();
    }

    private static void reply(OutputStream out, int code, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 403 ? "Forbidden" : "Error";
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static boolean allowed(String target, Set<String> allow) {
        if (allow.contains("*") || allow.contains(target)) {
            return true;
        }
        if (allow.isEmpty()) {
            return target.startsWith("127.0.0.1:") || target.startsWith("localhost:");
        }
        return false;
    }

    private static String param(String requestLine, String key) {
        int q = requestLine.indexOf('?');
        if (q < 0) {
            return null;
        }
        int end = requestLine.indexOf(' ', q);
        String rawQuery = end < 0 ? requestLine.substring(q + 1) : requestLine.substring(q + 1, end);
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String header(String head, String name) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // already gone
        }
    }

    /**
     * Deliberate faults, so the report can show what a real proxy does to a
     * streamed tunnel:
     *   buffer=N  release the downstream in N-byte blocks, like a coalescing proxy
     *   delay=N   wait N ms per read, like a slow link
     *   drop=N    discard the first N downstream bytes, like a truncating middlebox
     */
    private record Chaos(int buffer, int delay, long drop) {
        static final Chaos NONE = new Chaos(0, 0, 0);

        static Chaos parse(String spec) {
            if (spec == null || spec.isBlank()) {
                return NONE;
            }
            int buffer = 0;
            int delay = 0;
            long drop = 0;
            for (String part : spec.split("[&;]")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = part.substring(0, eq).trim();
                String value = part.substring(eq + 1).trim();
                try {
                    switch (key) {
                        case "buffer" -> buffer = Integer.parseInt(value);
                        case "delay" -> delay = Integer.parseInt(value);
                        case "drop" -> drop = Long.parseLong(value);
                        default -> {
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // a malformed knob is not worth killing a tunnel over
                }
            }
            return new Chaos(Math.max(0, buffer), Math.max(0, delay), Math.max(0, drop));
        }

        boolean isNone() {
            return buffer == 0 && delay == 0 && drop == 0;
        }

        @Override
        public String toString() {
            return "buffer=" + buffer + " delay=" + delay + " drop=" + drop;
        }
    }
}
