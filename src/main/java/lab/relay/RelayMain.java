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
import lab.tunnel.ChunkedInputStream;
import lab.tunnel.ChunkedOutputStream;

/**
 * Relay: reads one HTTP request head, answers 200 + Transfer-Encoding: chunked
 * before any body byte has arrived, then splices the chunked body to a raw TCP
 * connection to the RMI endpoint.
 *
 * The head is written by hand for one reason: the 200 must reach the client
 * while the request body is still empty, because the client will not send a
 * single body byte until it sees that 200. Anything that buffers the head
 * (com.sun.net.httpserver does) turns this into a deadlock.
 */
public final class RelayMain {
    private static final int MAX_HEAD = 8192;

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
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String head = readHead(in);
            String requestLine = head.substring(0, head.indexOf("\r\n"));
            if (!requestLine.startsWith("POST /tunnel?")) {
                reply(out, 200, "rmi-tunnel-lab relay\nuse POST /tunnel?target=host:port\n");
                closeQuietly(client);
                return;
            }

            String target = param(requestLine, "target");
            if (target == null) {
                reply(out, 400, "missing ?target=host:port\n");
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

            Socket upstream = new Socket();
            upstream.connect(new InetSocketAddress(target.substring(0, colon),
                    Integer.parseInt(target.substring(colon + 1))), 5000);
            upstream.setTcpNoDelay(true);

            // The head goes out now, with no body byte behind it.
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            System.out.println("tunnel up    : " + target + "  <- " + client.getRemoteSocketAddress());
            System.out.flush();

            ChunkedInputStream fromClient = new ChunkedInputStream(in);
            ChunkedOutputStream toClient = new ChunkedOutputStream(out);
            InputStream fromUpstream = upstream.getInputStream();
            OutputStream toUpstream = upstream.getOutputStream();

            Thread down = new Thread(() -> {
                pump(fromUpstream, toClient);
                closeQuietly(upstream);
                closeQuietly(toClient);
                closeQuietly(client);
            }, "relay-down");
            down.setDaemon(true);
            down.start();

            pump(fromClient, toUpstream);
            closeQuietly(upstream);
            closeQuietly(toClient);
            closeQuietly(client);
        } catch (Exception e) {
            System.out.println("tunnel err   : " + e);
            closeQuietly(client);
        }
    }

    private static void pump(InputStream in, OutputStream out) {
        byte[] buf = new byte[32 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // either side went away; the opposite pump will notice
        }
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

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // already gone
        }
    }
}
