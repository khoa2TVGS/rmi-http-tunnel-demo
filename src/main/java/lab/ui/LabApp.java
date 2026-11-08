package lab.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.rmi.server.RMISocketFactory;
import java.util.HashMap;
import java.util.Map;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import lab.client.LabClient;
import lab.common.Bytes;
import lab.common.FilterSpec;
import lab.common.ImageLab;
import lab.common.ImageResult;
import lab.common.SynthImage;
import lab.tunnel.TunnelSocketFactory;

public class LabApp extends Application {

    private final ComboBox<String> transport = new ComboBox<>();
    private final TextField hostField = new TextField("127.0.0.1");
    private final TextField portField = new TextField("1099");
    private final TextField relayField = new TextField("127.0.0.1:8080");
    private final Label connLabel = new Label("not connected");
    private final Button connectButton = new Button("Connect");

    private final ToggleGroup sampleGroup = new ToggleGroup();
    private final CheckBox noiseBox = new CheckBox("add noise");
    private final ComboBox<String> filterBox = new ComboBox<>();
    private final Slider radiusSlider = new Slider(1, 24, 6);
    private final Label radiusValue = new Label("6");
    private final Button runButton = new Button("Run");

    private final ImageView beforeView = new ImageView();
    private final ImageView afterView = new ImageView();

    private final Label totalLabel = new Label("-");
    private final Label computeLabel = new Label("-");
    private final Label transferLabel = new Label("-");
    private final Label bytesLabel = new Label("-");
    private final Label shaLabel = new Label("-");

    private ImageLab lab;
    private byte[] sourcePng;
    private String sourceSha;
    private long lastRoundTripMs;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        BorderPane root = buildUi();
        Scene scene = new Scene(root, 1120, 720);
        if (getParameters().getRaw().contains("--smoke")) {
            System.out.println("UI smoke: scene built (" + root.getCenter().getClass().getSimpleName() + ")");
            Platform.exit();
            return;
        }
        stage.setTitle("RMI Tunnel Lab");
        stage.setScene(scene);
        stage.show();
        regenerate(null);
    }

    private BorderPane buildUi() {
        BorderPane root = new BorderPane();
        root.setTop(toolbar());
        root.setLeft(controls());
        root.setCenter(imageArea());
        root.setBottom(statsBar());
        return root;
    }

    private Node toolbar() {
        transport.getItems().addAll("Direct", "Tunnel (chunked)");
        transport.setValue("Direct");
        hostField.setPrefWidth(110);
        portField.setPrefWidth(60);
        relayField.setPrefWidth(130);
        connectButton.setOnAction(e -> connect());
        connLabel.setStyle("-fx-text-fill: #4b5563;");

        HBox bar = new HBox(8, label("Transport"), transport,
                label("RMI host"), hostField,
                label("port"), portField,
                label("Relay"), relayField,
                connectButton, connLabel);
        bar.setPadding(new Insets(10, 12, 10, 12));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #ffffff; -fx-border-color: transparent transparent #e5e7eb transparent;");
        return bar;
    }

    private Node controls() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        box.setPrefWidth(330);
        box.setStyle("-fx-background-color: #f9fafb; -fx-border-color: transparent #e5e7eb transparent transparent;");

        VBox samples = new VBox(4);
        for (int size : new int[] {512, 1024, 2048, 4096}) {
            RadioButton rb = new RadioButton(size + " x " + size);
            rb.setToggleGroup(sampleGroup);
            rb.setUserData(size);
            rb.setSelected(size == 1024);
            samples.getChildren().add(rb);
        }
        sampleGroup.selectedToggleProperty().addListener((o, a, b) -> regenerate(null));
        noiseBox.selectedProperty().addListener((o, a, b) -> regenerate(null));

        Button open = new Button("Open image...");
        open.setMaxWidth(Double.MAX_VALUE);
        open.setOnAction(e -> openFile());

        filterBox.getItems().addAll("grayscale", "posterize", "sobel", "blur", "resize");
        filterBox.setValue("grayscale");
        filterBox.setMaxWidth(Double.MAX_VALUE);
        filterBox.valueProperty().addListener((o, a, b) ->
                radiusSlider.setDisable(!("blur".equals(b))));

        radiusSlider.setShowTickMarks(false);
        radiusSlider.valueProperty().addListener((o, a, b) ->
                radiusValue.setText(String.valueOf(Math.round(b.doubleValue()))));
        HBox radiusRow = new HBox(8, radiusSlider, radiusValue);
        HBox.setHgrow(radiusSlider, Priority.ALWAYS);
        radiusRow.setAlignment(Pos.CENTER_LEFT);
        radiusSlider.setDisable(true);

        runButton.setMaxWidth(Double.MAX_VALUE);
        runButton.setDefaultButton(true);
        runButton.setDisable(true);
        runButton.setOnAction(e -> run());

        box.getChildren().addAll(
                label("Sample"), samples, noiseBox, open,
                new Separator(),
                label("Filter"), filterBox,
                label("Blur radius"), radiusRow,
                runButton);
        return box;
    }

    private Node imageArea() {
        beforeView.setPreserveRatio(true);
        afterView.setPreserveRatio(true);
        beforeView.setFitWidth(430);
        beforeView.setFitHeight(430);
        afterView.setFitWidth(430);
        afterView.setFitHeight(430);

        HBox row = new HBox(16, imagePane("before", beforeView), imagePane("after", afterView));
        row.setPadding(new Insets(12));
        return row;
    }

    private Node imagePane(String title, ImageView view) {
        StackPane holder = new StackPane(view);
        holder.setMinSize(240, 240);
        holder.setStyle("-fx-background-color: #ffffff; -fx-border-color: #d1d5db;");
        VBox box = new VBox(6, label(title), holder);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private Node statsBar() {
        HBox bar = new HBox(28,
                stat("round trip", totalLabel),
                stat("server compute", computeLabel),
                stat("transfer (approx)", transferLabel),
                stat("bytes", bytesLabel),
                stat("integrity", shaLabel));
        bar.setPadding(new Insets(10, 12, 12, 12));
        bar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e7eb transparent transparent transparent;");
        return bar;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: #6b7280;");
        return l;
    }

    private Node stat(String caption, Label value) {
        VBox v = new VBox(2, label(caption), value);
        value.setStyle("-fx-font-size: 13px; -fx-text-fill: #111827;");
        return v;
    }

    private int selectedSize() {
        Toggle t = sampleGroup.getSelectedToggle();
        return t == null ? 1024 : (Integer) t.getUserData();
    }

    private void regenerate(Runnable onDone) {
        int size = selectedSize();
        boolean noise = noiseBox.isSelected();
        runButton.setDisable(true);
        Task<Void> task = new Task<>() {
            private BufferedImage img;
            private byte[] png;

            @Override
            protected Void call() {
                img = SynthImage.generate(size, noise);
                png = SynthImage.toPng(img);
                return null;
            }

            @Override
            protected void succeeded() {
                sourcePng = png;
                sourceSha = Bytes.sha256Hex(png);
                beforeView.setImage(toFx(img));
                afterView.setImage(null);
                bytesLabel.setText("source " + Bytes.human(png.length));
                shaLabel.setText("-");
                runButton.setDisable(lab == null);
                if (onDone != null) {
                    onDone.run();
                }
            }
        };
        daemon(task, "sample");
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
        RMISocketFactory factory = null;
        if ("Tunnel (chunked)".equals(mode)) {
            String relay = relayField.getText().trim();
            int colon = relay.lastIndexOf(':');
            if (colon <= 0) {
                connLabel.setText("relay must be host:port");
                return;
            }
            try {
                factory = new TunnelSocketFactory(relay.substring(0, colon),
                        Integer.parseInt(relay.substring(colon + 1)));
            } catch (NumberFormatException e) {
                connLabel.setText("bad relay port");
                return;
            }
        }
        RMISocketFactory chosen = factory;
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
                lab = remote;
                // RMI latches the global socket factory; switching modes needs a restart.
                transport.setDisable(true);
                relayField.setDisable(true);
                connLabel.setText("connected via " + mode + ": " + getValue());
                connectButton.setDisable(false);
                runButton.setDisable(sourcePng == null);
            }

            @Override
            protected void failed() {
                lab = null;
                connLabel.setText("connect failed: " + getException());
                connectButton.setDisable(false);
            }
        };
        daemon(task, "connect");
    }

    private void run() {
        if (lab == null || sourcePng == null) {
            return;
        }
        Map<String, Double> params = new HashMap<>();
        params.put("radius", radiusSlider.getValue());
        params.put("scale", 0.5);
        params.put("levels", 4.0);
        FilterSpec spec = new FilterSpec(filterBox.getValue(), params);
        byte[] payload = sourcePng;
        String sentSha = sourceSha;
        runButton.setDisable(true);
        totalLabel.setText("running ...");

        Task<ImageResult> task = new Task<>() {
            @Override
            protected ImageResult call() throws Exception {
                long t0 = System.nanoTime();
                ImageResult r = lab.transform(payload, spec);
                lastRoundTripMs = (System.nanoTime() - t0) / 1_000_000L;
                return r;
            }

            @Override
            protected void succeeded() {
                ImageResult r = getValue();
                boolean requestOk = sentSha.equals(r.sourceSha256());
                boolean responseOk = Bytes.sha256Hex(r.png()).equals(r.pngSha256());
                afterView.setImage(toFx(SynthImage.fromPng(r.png())));
                totalLabel.setText(lastRoundTripMs + " ms");
                computeLabel.setText(r.computeMillis() + " ms");
                transferLabel.setText(Math.max(0, lastRoundTripMs - r.computeMillis()) + " ms");
                bytesLabel.setText("up " + Bytes.human(payload.length)
                        + ", down " + Bytes.human(r.png().length));
                shaLabel.setText(requestOk && responseOk ? "ok (req + resp)" : "MISMATCH");
                runButton.setDisable(false);
            }

            @Override
            protected void failed() {
                totalLabel.setText("failed");
                computeLabel.setText("-");
                transferLabel.setText("-");
                shaLabel.setText(String.valueOf(getException()));
                runButton.setDisable(false);
            }
        };
        daemon(task, "run");
    }

    private void openFile() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
        File file = chooser.showOpenDialog(null);
        if (file == null) {
            return;
        }
        try {
            BufferedImage img = SynthImage.fromPng(Files.readAllBytes(file.toPath()));
            sourcePng = SynthImage.toPng(img);
            sourceSha = Bytes.sha256Hex(sourcePng);
            beforeView.setImage(toFx(img));
            afterView.setImage(null);
            bytesLabel.setText("source " + Bytes.human(sourcePng.length));
            shaLabel.setText("-");
            runButton.setDisable(lab == null);
        } catch (Exception ex) {
            connLabel.setText("open failed: " + ex.getMessage());
        }
    }

    private static Image toFx(BufferedImage img) {
        return new Image(new ByteArrayInputStream(SynthImage.toPng(img)));
    }

    private static void daemon(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
