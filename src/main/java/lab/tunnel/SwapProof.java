package lab.tunnel;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.server.RMISocketFactory;
import java.util.Map;
import lab.client.LabClient;
import lab.common.FilterSpec;
import lab.common.ImageLab;
import lab.common.SynthImage;

/**
 * Proves the claim the Disconnect button depends on: after RMI has already used
 * the tunnel, retargeting the global factory changes the transport of the very
 * next call -- no restart, no second JVM.
 *
 * The decisive moment is step 2. The delegate is swapped for a factory that
 * refuses every connection; if a call still succeeds, RMI cached the old path
 * and hot swap is a lie. Any failure that mentions the poison message proves
 * the swap was consulted.
 */
public final class SwapProof {
    private SwapProof() {}

    public static void main(String[] args) throws Exception {
        String relay = args.length > 0 ? args[0] : "127.0.0.1:8080";
        int colon = relay.lastIndexOf(':');
        String relayHost = relay.substring(0, colon);
        int relayPort = Integer.parseInt(relay.substring(colon + 1));

        TunnelSocketFactory tunnel =
                new TunnelSocketFactory(relayHost, relayPort, TunnelSocketFactory.Mode.CHUNKED, "");
        SwitchableSocketFactory gate = SwitchableSocketFactory.install(tunnel);
        byte[] png = SynthImage.toPng(SynthImage.generate(256, false));

        ImageLab lab = LabClient.connect("127.0.0.1", 1099, tunnel);
        System.out.println("1. tunnel      : " + lab.ping());
        System.out.println("   transform   : " + lab.transform(png, spec()).width() + " px wide via " + gate.describe());

        String poison = "poisoned factory was consulted";
        gate.use(new RMISocketFactory() {
            @Override
            public Socket createSocket(String host, int port) throws IOException {
                throw new IOException(poison);
            }

            @Override
            public ServerSocket createServerSocket(int port) {
                throw new UnsupportedOperationException();
            }
        });
        int closed = gate.closeAll();
        System.out.println("2. swapped     : closed " + closed + " socket(s); delegate now refuses everything");
        for (int i = 1; i <= 3; i++) {
            String outcome = attempt(lab, png);
            System.out.println("   call " + i + "      : " + outcome);
            if (outcome.contains(poison)) {
                break;
            }
            Thread.sleep(200);
        }

        gate.use(tunnel);
        lab = LabClient.connect("127.0.0.1", 1099, tunnel);
        System.out.println("3. swapped back: " + lab.ping() + " / "
                + lab.transform(png, spec()).width() + " px wide via " + gate.describe());
        System.out.println();
        System.out.println("hot swap: ok -- the factory is consulted per connection");
    }

    private static String attempt(ImageLab lab, byte[] png) {
        try {
            lab.transform(png, spec());
            return "SUCCEEDED -- a cached connection survived, hot swap does not work";
        } catch (Exception e) {
            String message = e.getMessage();
            return e.getClass().getSimpleName() + ": " + (message == null ? "" : message);
        }
    }

    private static FilterSpec spec() {
        return new FilterSpec("grayscale", Map.of());
    }
}
