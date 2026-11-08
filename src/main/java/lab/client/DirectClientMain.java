package lab.client;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import lab.common.Bytes;
import lab.common.FilterSpec;
import lab.common.ImageLab;
import lab.common.ImageResult;
import lab.common.SynthImage;

/** Headless baseline client. Measures one direct RMI round trip. */
public final class DirectClientMain {
    private DirectClientMain() {}

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 1099;
        int size = 1024;
        boolean noise = false;
        String filter = "grayscale";
        double radius = 6;
        int repeat = 1;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--size" -> size = Integer.parseInt(args[++i]);
                case "--noise" -> noise = true;
                case "--filter" -> filter = args[++i];
                case "--radius" -> radius = Double.parseDouble(args[++i]);
                case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("unknown argument: " + a);
                    System.exit(2);
                }
            }
        }

        ImageLab lab = LabClient.connect(host, port, null);
        System.out.println("ping        : " + lab.ping());

        BufferedImage img = SynthImage.generate(size, noise);
        byte[] png = SynthImage.toPng(img);
        String sentSha = Bytes.sha256Hex(png);

        Map<String, Double> params = new HashMap<>();
        params.put("radius", radius);
        params.put("scale", 0.5);
        params.put("levels", 4.0);
        FilterSpec spec = new FilterSpec(filter, params);

        long roundTripTotal = 0L;
        long computeTotal = 0L;
        ImageResult last = null;
        for (int i = 0; i < repeat; i++) {
            long t0 = System.nanoTime();
            last = lab.transform(png, spec);
            long t1 = System.nanoTime();
            roundTripTotal += (t1 - t0) / 1_000_000L;
            computeTotal += last.computeMillis();
        }

        long roundTrip = roundTripTotal / repeat;
        long compute = computeTotal / repeat;
        boolean requestOk = sentSha.equals(last.sourceSha256());
        boolean responseOk = Bytes.sha256Hex(last.png()).equals(last.pngSha256());

        System.out.printf("source      : %d x %d, %s, sha %s%n",
                img.getWidth(), img.getHeight(), Bytes.human(png.length), sentSha.substring(0, 16));
        System.out.printf("filter      : %s %s%n", filter, params);
        System.out.printf("round trip  : %d ms (avg of %d)%n", roundTrip, repeat);
        System.out.printf("server work : %d ms%n", compute);
        System.out.printf("transfer    : %d ms (approx, round trip - server work)%n",
                Math.max(0, roundTrip - compute));
        System.out.printf("result      : %d x %d, %s%n",
                last.width(), last.height(), Bytes.human(last.png().length));
        System.out.printf("integrity   : request %s, response %s%n",
                requestOk ? "OK" : "MISMATCH", responseOk ? "OK" : "MISMATCH");

        if (!requestOk || !responseOk) {
            System.exit(1);
        }
    }
}
