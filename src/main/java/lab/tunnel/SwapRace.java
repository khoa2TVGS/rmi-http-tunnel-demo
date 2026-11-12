package lab.tunnel;

import java.util.Map;
import lab.client.LabClient;
import lab.common.FilterSpec;
import lab.common.ImageLab;
import lab.common.SynthImage;

/** Throwaway probe: connect/disconnect repeatedly across transports in one JVM. */
public final class SwapRace {
    private SwapRace() {}

    public static void main(String[] args) throws Exception {
        String[] modes = args.length > 0 ? args
                : new String[] {"direct", "chunked", "direct", "chunked", "direct", "chunked"};
        byte[] png = SynthImage.toPng(SynthImage.generate(256, false));
        FilterSpec spec = new FilterSpec("grayscale", Map.of());
        int failures = 0;
        for (String mode : modes) {
            TunnelSocketFactory factory = "chunked".equals(mode)
                    ? new TunnelSocketFactory("127.0.0.1", 8080, TunnelSocketFactory.Mode.CHUNKED, "")
                    : null;
            try {
                long t0 = System.nanoTime();
                ImageLab lab = LabClient.connect("127.0.0.1", 1099, factory);
                int width = lab.transform(png, spec).width();
                System.out.println(mode + " ok      : " + width + " px, sockets "
                        + SwitchableSocketFactory.installed().openSockets() + ", "
                        + (System.nanoTime() - t0) / 1_000_000L + " ms");
            } catch (Exception e) {
                failures++;
                System.out.println(mode + " FAILED  : " + e);
            }
            LabClient.disconnect();
        }
        System.out.println(failures == 0 ? "all transitions ok" : failures + " transition(s) failed");
    }
}
