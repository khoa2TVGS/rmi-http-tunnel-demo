package lab.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.server.RMISocketFactory;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import lab.client.LabClient;
import lab.common.ImageLab;
import lab.tunnel.TunnelSocketFactory;

/**
 * The shell: one connection bar, one tab per tool.
 *
 * RMI latches the JVM's socket factory on first use, so the connection is made
 * once through a switchable factory; Disconnect closes the live sockets and
 * retargets it, which is how the transport can change without restarting. The
 * tools themselves never care which transport is live.
 */
public class LabApp extends Application {

    private final ComboBox<String> transport = new ComboBox<>();
    private final TextField hostField = new TextField("127.0.0.1");
    private final TextField portField = new TextField("1099");
    private final TextField relayField = new TextField("127.0.0.1:8080");
    private final TextField chaosField = new TextField();
    private final Label connLabel = new Label("not connected");
    private final Button connectButton = new Button("Connect");
    private final Button disconnectButton = new Button("Disconnect");

    private final TabPane tabs = new TabPane();
    private final ImageTool imageTool = new ImageTool();
    private final TransformTool transformTool = new TransformTool();
    private final HistogramTool histogramTool = new HistogramTool();
    private final PaletteTool paletteTool = new PaletteTool();
    private final BenchTool benchTool = new BenchTool();
    private final TunnelTool tunnelTool = new TunnelTool(this::relayAddress, this::chaos);
    private final List<Tool> tools =
            List.of(imageTool, transformTool, histogramTool, paletteTool, benchTool, tunnelTool);

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        BorderPane root = buildUi();
        Scene scene = new Scene(root, 1280, 720);

        String tab = value(args, "--tab");
        if (tab != null) {
            try {
                tabs.getSelectionModel().select(Integer.parseInt(tab));
            } catch (NumberFormatException ignored) {
                // keep the default tab
            }
        }

        if (args.contains("--smoke")) {
            System.out.println("UI smoke: scene built (" + tabs.getTabs().size() + " tabs)");
            Platform.exit();
            return;
        }

        if (args.contains("--swap-demo")) {
            swapDemo();
            Platform.exit();
            return;
        }

        stage.setTitle("RMI Tunnel Lab");
        stage.setScene(scene);
        // Open no larger than the usable screen. At 150% scaling a 1280x720 window
        // is wider than a 1920x1080 display in logical pixels, and the window
        // manager then clips it rather than letting the layout shrink.
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        double winWidth = Math.min(1280, screen.getWidth() - 40);
        double winHeight = Math.min(720, screen.getHeight() - 40);
        String win = value(args, "--win");
        if (win != null && win.contains("x")) {
            String[] wh = win.split("x");
            winWidth = Double.parseDouble(wh[0]);
            winHeight = Double.parseDouble(wh[1]);
        }
        stage.setWidth(winWidth);
        stage.setHeight(winHeight);
        stage.setMinWidth(720);
        stage.setMinHeight(480);
        stage.show();

        String shot = value(args, "--shot");
        if (shot != null) {
            // figures for the report, rendered by JavaFX itself
            javafx.animation.PauseTransition wait =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(1200));
            wait.setOnFinished(event -> {
                try {
                    javafx.scene.Parent content = (javafx.scene.Parent) tabs.getSelectionModel().getSelectedItem().getContent();
                    content.applyCss();
                    content.layout();
                    Path path = Path.of(shot);
                    writeShot(scene.snapshot(null), path);
                    System.out.println("shot        : " + path.toAbsolutePath());
                } catch (Exception e) {
                    System.out.println("shot failed : " + e);
                }
                Platform.exit();
            });
            wait.play();
        }
    }

    private static String value(List<String> args, String key) {
        int i = args.indexOf(key);
        return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : null;
    }

    private static void writeShot(javafx.scene.image.Image image, Path path) throws java.io.IOException {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            reader.getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w);
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        if (path.toAbsolutePath().getParent() != null) {
            Files.createDirectories(path.toAbsolutePath().getParent());
        }
        javax.imageio.ImageIO.write(out, "png", path.toFile());
    }

    private BorderPane buildUi() {
        tabs.getTabs().clear();
        for (Tool tool : tools) {
            tabs.getTabs().add(new Tab(tool.title(), tool.view()));
        }
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane root = new BorderPane();
        root.setTop(toolbar());
        root.setCenter(tabs);
        return root;
    }

    private Node toolbar() {
        transport.getItems().addAll("Direct", "Tunnel (chunked)", "Tunnel (CONNECT)");
        transport.setValue("Direct");
        transport.setPrefWidth(150);
        hostField.setPrefWidth(100);
        portField.setPrefWidth(52);
        relayField.setPrefWidth(115);
        chaosField.setPromptText("buffer=65536&delay=50");
        chaosField.setPrefWidth(160);
        Tooltip.install(chaosField, new Tooltip("Optional relay chaos: buffer=N, delay=N(ms), drop=N(bytes)"));
        connectButton.setOnAction(e -> connect());
        disconnectButton.setDisable(true);
        disconnectButton.setOnAction(e -> disconnect());
        connLabel.setStyle("-fx-text-fill: #4b5563;");
        connLabel.setWrapText(true);

        HBox bar = new HBox(6, Ui.label("Transport"), transport,
                Ui.label("RMI host"), hostField,
                Ui.label("port"), portField,
                Ui.label("Relay"), relayField,
                Ui.label("chaos"), chaosField,
                connectButton, disconnectButton, connLabel);
        bar.setPadding(new Insets(10, 12, 10, 12));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #ffffff; -fx-border-color: transparent transparent #e5e7eb transparent;");
        return bar;
    }

    private String relayAddress() {
        return relayField.getText().trim();
    }

    private String chaos() {
        return chaosField.getText().trim();
    }

    /** Builds the transport for a mode; null means "do not touch RMI, use its default". */
    static RMISocketFactory factoryFor(String mode, String relay, String chaos) {
        if ("Direct".equals(mode)) {
            return null;
        }
        int colon = relay.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("relay must be host:port");
        }
        TunnelSocketFactory.Mode form = "Tunnel (CONNECT)".equals(mode)
                ? TunnelSocketFactory.Mode.CONNECT
                : TunnelSocketFactory.Mode.CHUNKED;
        int port;
        try {
            port = Integer.parseInt(relay.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad relay port");
        }
        return new TunnelSocketFactory(relay.substring(0, colon), port, form, chaos);
    }

    /**
     * Diagnostic flag: does exactly what Connect and Disconnect do, without a
     * mouse. Direct, then tunnel, then direct again in one JVM -- if the third
     * connection works, the transport really was swapped twice at runtime.
     */
    private void swapDemo() {
        String host = hostField.getText().trim();
        int port = Integer.parseInt(portField.getText().trim());
        String[] modes = {"Direct", "Tunnel (chunked)", "Direct"};
        byte[] png = lab.common.SynthImage.toPng(lab.common.SynthImage.generate(256, false));
        lab.common.FilterSpec spec = new lab.common.FilterSpec("grayscale", java.util.Map.of());
        for (String mode : modes) {
            String relay = "Direct".equals(mode) ? "" : relayAddress();
            try {
                ImageLab lab = LabClient.connect(host, port, factoryFor(mode, relay, chaos()));
                System.out.println("connect    : " + mode + " -> " + lab.ping()
                        + " / " + lab.transform(png, spec).width() + " px back");
            } catch (Exception e) {
                System.out.println("connect    : " + mode + " FAILED: " + e);
                continue;
            }
            System.out.println("disconnect : closed " + LabClient.disconnect() + " socket(s)");
        }
    }

    private void setConnected(boolean connected) {
        transport.setDisable(connected);
        hostField.setDisable(connected);
        portField.setDisable(connected);
        relayField.setDisable(connected);
        chaosField.setDisable(connected);
        connectButton.setDisable(connected);
        disconnectButton.setDisable(!connected);
    }

    private void connect() {
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            connLabel.setText("bad port");
            return;
        }
        String mode = transport.getValue();
        RMISocketFactory chosen;
        try {
            chosen = factoryFor(mode, relayAddress(), chaos());
        } catch (IllegalArgumentException e) {
            connLabel.setText(e.getMessage());
            return;
        }
        connectButton.setDisable(true);
        connLabel.setText(mode + " -> " + host + ":" + port + " ...");
        Task<String> task = new Task<>() {
            private ImageLab remote;

            @Override
            protected String call() throws Exception {
                remote = LabClient.connect(host, port, chosen);
                return remote.ping();
            }

            @Override
            protected void succeeded() {
                setConnected(true);
                connLabel.setText("connected via " + LabClient.describe() + ": " + getValue());
                for (Tool tool : tools) {
                    tool.onConnected(remote);
                }
            }

            @Override
            protected void failed() {
                connLabel.setText("connect failed: " + getException());
                connectButton.setDisable(false);
            }
        };
        Ui.daemon(task, "connect");
    }

    private void disconnect() {
        int closed = LabClient.disconnect();
        for (Tool tool : tools) {
            tool.onDisconnected();
        }
        setConnected(false);
        connLabel.setText("disconnected; closed " + closed + " socket(s). Pick a transport and connect again.");
    }
}
