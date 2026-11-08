package lab.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Random;
import lab.common.Bytes;

/**
 * D2 gate: send an arbitrary number of megabytes through the tunnel to a plain
 * echo server and compare what comes back. No RMI involved, so a failure here
 * is the codec's fault and nothing else's.
 */
public final class TunnelProbe {
    private TunnelProbe() {}

    /** @param megabytes payload size in MB; @return timing, integrity and a printable transcript. */
    public record Report(boolean identical, long millis, int bytes, String text) {}

    public static void main(String[] args) throws Exception {
        String relayHost = args.length > 0 ? args[0] : "127.0.0.1";
        int relayPort = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        int megabytes = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        Report report = probe(relayHost, relayPort, megabytes, "");
        System.out.print(report.text());
        if (!report.identical()) {
            System.exit(1);
        }
    }

    public static Report probe(String relayHost, int relayPort, int megabytes, String chaos)
            throws IOException, InterruptedException {
        StringBuilder log = new StringBuilder();
        ServerSocket echo = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        int echoPort = echo.getLocalPort();
        Thread acceptor = new Thread(() -> {
            try (Socket s = echo.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // done
            }
        }, "echo");
        acceptor.setDaemon(true);
        acceptor.start();
        log.append("echo target  : 127.0.0.1:").append(echoPort).append('\n');

        try {
            long openStart = System.nanoTime();
            TunnelSocket tunnel = TunnelClient.openChunked(relayHost, relayPort, "127.0.0.1", echoPort, chaos);
            log.append("relay        : ").append(relayHost).append(':').append(relayPort).append('\n');
            if (chaos != null && !chaos.isBlank()) {
                log.append("chaos        : ").append(chaos).append('\n');
            }
            log.append("tunnel open  : ").append((System.nanoTime() - openStart) / 1_000_000).append(" ms  ")
                    .append(tunnel).append('\n');

            byte[] payload = new byte[megabytes * 1024 * 1024];
            new Random(7L).nextBytes(payload);
            String sentSha = Bytes.sha256Hex(payload);

            byte[] echoed = new byte[payload.length];
            boolean[] readOk = {false};
            Thread reader = new Thread(() -> readOk[0] = readFully(tunnel.getInputStream(), echoed), "reader");
            reader.setDaemon(true);

            long start = System.nanoTime();
            reader.start();
            OutputStream out = tunnel.getOutputStream();
            for (int off = 0; off < payload.length; off += 32 * 1024) {
                int n = Math.min(32 * 1024, payload.length - off);
                out.write(payload, off, n);
            }
            out.flush();
            reader.join();
            long millis = Math.max(1, (System.nanoTime() - start) / 1_000_000);

            boolean identical = readOk[0] && Arrays.equals(payload, echoed);
            log.append("bytes        : ").append(Bytes.human(payload.length)).append(" each way\n");
            log.append("elapsed      : ").append(millis).append(" ms\n");
            log.append(String.format(java.util.Locale.ROOT, "throughput   : %.1f MB/s%n",
                    payload.length / 1048576.0 / (millis / 1000.0)));
            log.append("sha256       : ").append(sentSha, 0, 16).append(" ...\n");
            log.append("integrity    : ").append(identical ? "ok, byte-identical" : "MISMATCH").append('\n');
            tunnel.close();
            return new Report(identical, millis, payload.length, log.toString());
        } finally {
            echo.close();
        }
    }

    private static boolean readFully(InputStream in, byte[] dest) {
        try {
            int off = 0;
            while (off < dest.length) {
                int n = in.read(dest, off, dest.length - off);
                if (n < 0) {
                    return false;
                }
                off += n;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
