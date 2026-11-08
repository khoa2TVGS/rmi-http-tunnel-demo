package lab.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.rmi.server.RMISocketFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import lab.client.LabClient;
import lab.common.Bytes;
import lab.common.FilterSpec;
import lab.common.ImageLab;
import lab.common.ImageResult;
import lab.common.SynthImage;
import lab.tunnel.TunnelSocketFactory;

/**
 * One transport, one workload, one CSV row per payload size.
 *
 * Runs in a fresh JVM per transport on purpose: RMISocketFactory latches for
 * the lifetime of the process, so a single JVM cannot measure direct and
 * tunnel in the same run.
 */
public final class BenchMain {
    private static final String HEADER =
            "network,mode,workload,payload_bytes,iterations,mean_total_ms,mean_server_ms,mean_transfer_ms,mbps,sha256_ok";

    private BenchMain() {}

    public static void main(String[] args) throws Exception {
        String mode = "direct";
        String relay = "127.0.0.1:8080";
        String host = "127.0.0.1";
        int port = 1099;
        String workload = "echo";
        int iterations = 5;
        int warmup = 2;
        String network = "local";
        String out = null;
        List<String> sizes = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode" -> mode = args[++i];
                case "--relay" -> relay = args[++i];
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--workload" -> workload = args[++i];
                case "--iterations" -> iterations = Integer.parseInt(args[++i]);
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--network" -> network = args[++i];
                case "--out" -> out = args[++i];
                case "--sizes" -> sizes.addAll(Arrays.asList(args[++i].split(",")));
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (sizes.isEmpty()) {
            sizes = workload.equals("image")
                    ? new ArrayList<>(List.of("512", "1024", "2048"))
                    : new ArrayList<>(List.of("64k", "512k", "4m"));
        }

        ImageLab lab = LabClient.connect(host, port, factory(mode, relay));
        System.out.printf("bench        : mode=%s workload=%s iters=%d warmup=%d%n",
                mode, workload, iterations, warmup);

        List<String> rows = new ArrayList<>();
        for (String size : sizes) {
            String s = size.trim();
            rows.add(workload.equals("image")
                    ? imageRow(lab, mode, network, s, iterations, warmup)
                    : echoRow(lab, mode, network, s, iterations, warmup));
        }

        System.out.println();
        System.out.println(HEADER);
        for (String row : rows) {
            System.out.println(row);
        }

        if (out != null) {
            Path path = Path.of(out);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            boolean fresh = !Files.exists(path) || Files.size(path) == 0;
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) {
                    writer.write(HEADER + "\n");
                }
                for (String row : rows) {
                    writer.write(row + "\n");
                }
            }
            System.out.println();
            System.out.println("csv          : " + path.toAbsolutePath());
        }
    }

    private static String echoRow(ImageLab lab, String mode, String network, String size,
                                  int iterations, int warmup) throws IOException {
        int length = parseSize(size);
        byte[] payload = new byte[length];
        new Random(42L).nextBytes(payload);
        String sha = Bytes.sha256Hex(payload);

        long total = 0L;
        int ok = 0;
        for (int i = 0; i < warmup + iterations; i++) {
            long start = System.nanoTime();
            byte[] back = lab.echo(payload);
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            if (i >= warmup) {
                total += elapsed;
                if (Arrays.equals(payload, back)) {
                    ok++;
                }
            }
        }
        long mean = total / iterations;
        double mbps = payload.length * 2.0 / 1048576.0 / Math.max(0.001, mean / 1000.0);
        return String.format(Locale.ROOT, "%s,%s,echo,%d,%d,%d,,%d,%.1f,%s",
                network, mode, payload.length, iterations, mean, mean, mbps, ok == iterations);
    }

    private static String imageRow(ImageLab lab, String mode, String network, String px,
                                   int iterations, int warmup) throws IOException {
        int size = Integer.parseInt(px);
        byte[] png = SynthImage.toPng(SynthImage.generate(size, false));
        String sha = Bytes.sha256Hex(png);
        Map<String, Double> params = new HashMap<>();
        params.put("radius", 6.0);
        FilterSpec spec = new FilterSpec("grayscale", params);

        long total = 0L;
        long compute = 0L;
        int ok = 0;
        for (int i = 0; i < warmup + iterations; i++) {
            long start = System.nanoTime();
            ImageResult result = lab.transform(png, spec);
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            if (i >= warmup) {
                total += elapsed;
                compute += result.computeMillis();
                if (sha.equals(result.sourceSha256())) {
                    ok++;
                }
            }
        }
        long mean = total / iterations;
        long meanCompute = compute / iterations;
        long transfer = Math.max(0L, mean - meanCompute);
        double mbps = png.length * 2.0 / 1048576.0 / Math.max(0.001, mean / 1000.0);
        return String.format(Locale.ROOT, "%s,%s,image,%d,%d,%d,%d,%d,%.1f,%s",
                network, mode, png.length, iterations, mean, meanCompute, transfer, mbps, ok == iterations);
    }

    private static RMISocketFactory factory(String mode, String relay) {
        if (mode.equals("direct")) {
            return null;
        }
        int colon = relay.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("--relay must be host:port, got " + relay);
        }
        TunnelSocketFactory.Mode m = mode.equals("connect")
                ? TunnelSocketFactory.Mode.CONNECT
                : TunnelSocketFactory.Mode.CHUNKED;
        return new TunnelSocketFactory(relay.substring(0, colon),
                Integer.parseInt(relay.substring(colon + 1)), m, "");
    }

    private static int parseSize(String value) {
        String v = value.toLowerCase();
        if (v.endsWith("k")) {
            return Integer.parseInt(v.substring(0, v.length() - 1)) * 1024;
        }
        if (v.endsWith("m")) {
            return Integer.parseInt(v.substring(0, v.length() - 1)) * 1024 * 1024;
        }
        return Integer.parseInt(v);
    }
}
