package lab.ui;

import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import lab.tunnel.TunnelProbe;
import lab.tunnel.WireCapture;

/**
 * Tool 3: the transport with RMI taken out of the picture.
 *
 * The relay, the chunked codec and the two HTTP forms can be inspected on their
 * own, so when an RMI call fails you know which half to blame.
 */
final class TunnelTool implements Tool {

    private final Supplier<String> relayAddress;
    private final Supplier<String> chaosSetting;
    private final TextField sizeField = new TextField("2");
    private static final String PROMPT =
            "Run probe or Capture handshake; the transcript appears here.";
    private final TextArea out = new TextArea();
    private final Label status = new Label("-");
    private final Button capture = new Button("Capture handshake");
    private final Button probe = new Button("Run probe");
    private Node view;

    TunnelTool(Supplier<String> relayAddress, Supplier<String> chaosSetting) {
        this.relayAddress = relayAddress;
        this.chaosSetting = chaosSetting;
    }

    @Override
    public String title() {
        return "Tunnel inspector";
    }

    @Override
    public Node view() {
        if (view == null) {
            view = build();
        }
        return view;
    }

    private Node build() {
        sizeField.setPrefWidth(60);
        sizeField.setTooltip(new Tooltip("payload size in MB"));

        capture.setOnAction(e -> runCapture());
        probe.setOnAction(e -> runProbe());

        status.setStyle("-fx-font-size: 13px; -fx-text-fill: #111827;");

        HBox bar = new HBox(10,
                Ui.label("payload MB"), sizeField,
                probe, capture,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                Ui.label("result"), status);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12));

        Label hint = new Label("Both actions spin up a throw-away echo server on loopback and reach it through the "
                + "relay named in the toolbar. The relay target is resolved by the relay, so the client never dials "
                + "the RMI ports. Chaos from the toolbar's chaos field applies here too.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");
        VBox top = new VBox(4, bar, hint);
        top.setPadding(new Insets(0, 12, 8, 12));

        out.setEditable(false);
        out.setPromptText(PROMPT);
        out.setWrapText(true);
        out.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
        BorderPane.setMargin(out, new Insets(0, 12, 12, 12));

        BorderPane pane = new BorderPane();
        pane.setTop(top);
        pane.setCenter(out);
        return pane;
    }

    private void runCapture() {
        String[] relay = relay();
        if (relay == null) {
            return;
        }
        busy(true, "capturing ...");
        Thread t = new Thread(() -> {
            try {
                String text = WireCapture.capture(relay[0], Integer.parseInt(relay[1]), chaosSetting.get());
                Platform.runLater(() -> {
                    out.appendText(text);
                    out.appendText("\n");
                    done(true, "handshake captured");
                });
            } catch (Exception e) {
                Platform.runLater(() -> fail("capture failed: " + e));
            }
        }, "capture");
        t.setDaemon(true);
        t.start();
    }

    private void runProbe() {
        String[] relay = relay();
        if (relay == null) {
            return;
        }
        int megabytes;
        try {
            megabytes = Integer.parseInt(sizeField.getText().trim());
        } catch (NumberFormatException e) {
            fail("payload must be a whole number of MB");
            return;
        }
        if (megabytes < 1 || megabytes > 64) {
            fail("payload must be between 1 and 64 MB");
            return;
        }
        busy(true, "probing ...");
        int size = megabytes;
        Thread t = new Thread(() -> {
            try {
                TunnelProbe.Report report = TunnelProbe.probe(relay[0], Integer.parseInt(relay[1]), size, chaosSetting.get());
                Platform.runLater(() -> {
                    out.appendText(report.text());
                    out.appendText("\n");
                    done(report.identical(), report.identical()
                            ? "byte-identical, " + report.millis() + " ms"
                            : "MISMATCH");
                });
            } catch (Exception e) {
                Platform.runLater(() -> fail("probe failed: " + e));
            }
        }, "probe");
        t.setDaemon(true);
        t.start();
    }

    /** @return {host, port} from the toolbar field, or null after reporting the problem. */
    private String[] relay() {
        String text = relayAddress.get().trim();
        int colon = text.lastIndexOf(':');
        if (colon <= 0) {
            fail("toolbar relay must be host:port");
            return null;
        }
        try {
            Integer.parseInt(text.substring(colon + 1));
        } catch (NumberFormatException e) {
            fail("toolbar relay port is not a number");
            return null;
        }
        return new String[] {text.substring(0, colon), text.substring(colon + 1)};
    }

    private void busy(boolean running, String text) {
        probe.setDisable(running);
        capture.setDisable(running);
        status.setText(text);
        status.setStyle("-fx-font-size: 13px; -fx-text-fill: #111827;");
    }

    private void done(boolean ok, String text) {
        busy(false, text);
        status.setStyle("-fx-font-size: 13px; -fx-text-fill: " + (ok ? "#047857" : "#b91c1c") + ";");
    }

    private void fail(String text) {
        busy(false, text);
        status.setStyle("-fx-font-size: 13px; -fx-text-fill: #b91c1c;");
        out.appendText(text + "\n");
    }
}
