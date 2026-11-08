package lab.ui;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.rmi.server.RMISocketFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
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

    private final CheckBox benchDirect = new CheckBox("direct");
    private final CheckBox benchChunked = new CheckBox("chunked");
    private final CheckBox benchConnect = new CheckBox("connect");
    private final CheckBox benchEcho = new CheckBox("echo");
    private final CheckBox benchImage = new CheckBox("image");
    private final TextField benchIters = new TextField("5");
    private final TextField benchNetwork = new TextField("local");
    private final TextArea benchLog = new TextArea();
    private final TableView<BenchRow> benchTable = new TableView<>();
    private final BarChart<String, Number> benchChart =
            new BarChart<>(new CategoryAxis(), new NumberAxis());
    private final Button benchRun = new Button("Run matrix");
    private final Button benchExport = new Button("Export CSV...");

    private ImageLab lab;
    private byte[] sourcePng;
    private String sourceSha;
    private long lastRoundTripMs;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        TabPane root = buildUi();
        Scene scene = new Scene(root, 1120, 760);

        String tab = value(args, "--tab");
        if (tab != null) {
            try {
                root.getSelectionModel().select(Integer.parseInt(tab));
            } catch (NumberFormatException ignored) {
                // keep the default tab
            }
        }

        if (args.contains("--smoke")) {
            System.out.println("UI smoke: scene built (" + root.getTabs().size() + " tabs)");
            Platform.exit();
            return;
        }

        stage.setTitle("RMI Tunnel Lab");
        stage.setScene(scene);
        stage.show();
        regenerate(null);

        String shot = value(args, "--shot");
        if (shot != null) {
            // figures for the report, rendered by JavaFX itself
            javafx.animation.PauseTransition wait =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(1200));
            wait.setOnFinished(event -> {
                try {
                    javafx.scene.Parent content = (javafx.scene.Parent) root.getSelectionModel().getSelectedItem().getContent();
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

    private TabPane buildUi() {
        BorderPane lab = new BorderPane();
        lab.setTop(toolbar());
        lab.setLeft(controls());
        lab.setCenter(imageArea());
        lab.setBottom(statsBar());

        TabPane tabs = new TabPane(new Tab("Image lab", lab), new Tab("Benchmark", benchmarkPane()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return tabs;
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
        radiusValue.setMinWidth(24);

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
        HBox row = new HBox(16, imagePane("before", beforeView), imagePane("after", afterView));
        row.setPadding(new Insets(12));
        row.setFillHeight(true);
        return row;
    }

    private Node imagePane(String title, ImageView view) {
        view.setPreserveRatio(true);
        StackPane holder = new StackPane(view);
        holder.setMinSize(240, 240);
        holder.setStyle("-fx-background-color: #ffffff; -fx-border-color: #d1d5db;");
        // fit the picture to whatever room the pane has instead of fixing 430px
        view.fitWidthProperty().bind(holder.widthProperty().subtract(16));
        view.fitHeightProperty().bind(holder.heightProperty().subtract(16));

        VBox box = new VBox(6, label(title), holder);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        VBox.setVgrow(holder, Priority.ALWAYS);
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

    private Node benchmarkPane() {
        for (CheckBox box : new CheckBox[] {benchDirect, benchChunked, benchConnect, benchEcho, benchImage}) {
            box.setSelected(!box.equals(benchConnect));
        }
        benchIters.setPrefWidth(60);
        benchNetwork.setPrefWidth(110);

        Label hint = new Label("Each mode runs in its own JVM: RMI latches the socket factory for the "
                + "life of the process, so one process cannot measure direct and tunnel.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");

        benchRun.setMaxWidth(Double.MAX_VALUE);
        benchExport.setMaxWidth(Double.MAX_VALUE);
        benchExport.setDisable(true);
        benchRun.setOnAction(e -> runBench());
        benchExport.setOnAction(e -> exportCsv());

        VBox side = new VBox(8,
                label("transport modes"), benchDirect, benchChunked, benchConnect,
                new Separator(),
                label("workloads"), benchEcho, benchImage,
                new Separator(),
                label("iterations"), benchIters,
                label("network label"), benchNetwork,
                benchRun, benchExport, hint);
        side.setPadding(new Insets(12));
        side.setPrefWidth(260);
        side.setStyle("-fx-background-color: #f9fafb; -fx-border-color: transparent #e5e7eb transparent transparent;");

        benchChart.setAnimated(false);
        benchChart.setLegendSide(javafx.geometry.Side.TOP);
        benchChart.setTitle("transport time per payload (ms, lower is better)");
        benchChart.setPrefHeight(260);
        benchChart.setCategoryGap(12);

        benchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        benchTable.getColumns().setAll(
                column("network", 70, BenchRow::network),
                column("mode", 70, BenchRow::mode),
                column("workload", 75, BenchRow::workload),
                column("payload", 80, r -> Bytes.human(r.payload())),
                column("total ms", 70, r -> String.valueOf(r.totalMs())),
                column("server ms", 70, r -> r.serverMs() < 0 ? "-" : String.valueOf(r.serverMs())),
                column("transfer ms", 80, r -> String.valueOf(r.transferMs())),
                column("MB/s", 60, r -> String.format(Locale.ROOT, "%.1f", r.mbps())),
                column("sha256", 70, r -> r.ok() ? "ok" : "MISMATCH"));
        VBox.setVgrow(benchTable, Priority.ALWAYS);

        benchLog.setEditable(false);
        benchLog.setPrefRowCount(6);
        benchLog.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");

        VBox chartBox = new VBox(benchChart);
        VBox.setVgrow(benchChart, Priority.ALWAYS);

        VBox main = new VBox(8, chartBox, benchTable, label("run log"), benchLog);
        main.setPadding(new Insets(12));
        main.setStyle("-fx-background-color: #ffffff;");
        VBox.setVgrow(benchTable, Priority.ALWAYS);

        BorderPane pane = new BorderPane();
        pane.setLeft(side);
        pane.setCenter(main);

        // Show the last run immediately; the CSV is the only state we keep.
        Path previous = Path.of("bench", "results.csv");
        if (Files.exists(previous)) {
            loadBenchCsv(previous);
        }
        return pane;
    }

    private static TableColumn<BenchRow, String> column(String title, double width,
                                                        java.util.function.Function<BenchRow, String> getter) {
        TableColumn<BenchRow, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(getter.apply(data.getValue())));
        return c;
    }

    private void runBench() {
        List<String> modes = new ArrayList<>();
        if (benchDirect.isSelected()) {
            modes.add("direct");
        }
        if (benchChunked.isSelected()) {
            modes.add("chunked");
        }
        if (benchConnect.isSelected()) {
            modes.add("connect");
        }
        List<String> workloads = new ArrayList<>();
        if (benchEcho.isSelected()) {
            workloads.add("echo");
        }
        if (benchImage.isSelected()) {
            workloads.add("image");
        }
        if (modes.isEmpty() || workloads.isEmpty()) {
            benchLog.appendText("select at least one transport mode and one workload\n");
            return;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(benchIters.getText().trim());
        } catch (NumberFormatException e) {
            benchLog.appendText("iterations must be a number\n");
            return;
        }
        if (iterations < 1 || iterations > 50) {
            benchLog.appendText("iterations must be between 1 and 50\n");
            return;
        }
        String typed = benchNetwork.getText().trim();
        String netLabel = typed.isEmpty() ? "local" : typed;
        Path out = Path.of("bench", "results.csv");

        benchRun.setDisable(true);
        benchExport.setDisable(true);
        benchLog.clear();
        benchTable.getItems().clear();
        benchChart.getData().clear();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Files.deleteIfExists(out);
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                String classpath = System.getProperty("java.class.path");
                for (String workload : workloads) {
                    for (String mode : modes) {
                        List<String> command = List.of(java, "-cp", classpath, "lab.bench.BenchMain",
                                "--mode", mode, "--workload", workload,
                                "--iterations", String.valueOf(iterations), "--warmup", "2",
                                "--network", netLabel, "--out", out.toString());
                        String shown = String.join(" ", command);
                        Platform.runLater(() -> benchLog.appendText("$ " + shown + "\n"));
                        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String text = line;
                                Platform.runLater(() -> benchLog.appendText(text + "\n"));
                            }
                        }
                        int code = process.waitFor();
                        if (code != 0) {
                            String note = "[exit " + code + "]\n";
                            Platform.runLater(() -> benchLog.appendText(note));
                        }
                    }
                }
                return null;
            }

            @Override
            protected void succeeded() {
                benchRun.setDisable(false);
                loadBenchCsv(out);
            }

            @Override
            protected void failed() {
                benchRun.setDisable(false);
                benchLog.appendText("bench failed: " + getException() + "\n");
            }
        };
        daemon(task, "bench");
    }

    private void loadBenchCsv(Path path) {
        if (!Files.exists(path)) {
            benchLog.appendText("no csv at " + path.toAbsolutePath() + "\n");
            return;
        }
        List<BenchRow> rows = new ArrayList<>();
        Map<String, XYChart.Series<String, Number>> series = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("network,")) {
                    continue;
                }
                String[] f = line.split(",", -1);
                if (f.length < 10) {
                    continue;
                }
                rows.add(new BenchRow(f[0], f[1], f[2], Long.parseLong(f[3]), Integer.parseInt(f[4]),
                        Long.parseLong(f[5]), f[6].isBlank() ? -1 : Long.parseLong(f[6]),
                        Long.parseLong(f[7]), Double.parseDouble(f[8]), Boolean.parseBoolean(f[9])));
            }
        } catch (Exception e) {
            benchLog.appendText("csv parse failed: " + e + "\n");
            return;
        }
        benchTable.getItems().setAll(rows);
        for (BenchRow row : rows) {
            XYChart.Series<String, Number> s = series.computeIfAbsent(row.mode(), name -> {
                XYChart.Series<String, Number> fresh = new XYChart.Series<>();
                fresh.setName(name);
                return fresh;
            });
            s.getData().add(new XYChart.Data<>(Bytes.human(row.payload()) + " " + row.workload(), row.transferMs()));
        }
        benchChart.getData().setAll(series.values());
        benchExport.setDisable(rows.isEmpty());
        benchLog.appendText("loaded " + rows.size() + " rows from " + path.toAbsolutePath() + "\n");
    }

    private void exportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("benchmark.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File target = chooser.showSaveDialog(null);
        if (target == null) {
            return;
        }
        try {
            Files.copy(Path.of("bench", "results.csv"), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            benchLog.appendText("exported to " + target.getAbsolutePath() + "\n");
        } catch (Exception e) {
            benchLog.appendText("export failed: " + e + "\n");
        }
    }

    private record BenchRow(String network, String mode, String workload, long payload, int iterations,
                            long totalMs, long serverMs, long transferMs, double mbps, boolean ok) {}

    private static Image toFx(BufferedImage img) {
        return new Image(new ByteArrayInputStream(SynthImage.toPng(img)));
    }

    private static void daemon(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
