package lab.ui;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import lab.common.Bytes;

/** Tool 2: run the transport matrix and read the CSV back. */
final class BenchTool implements Tool {

    private final CheckBox benchDirect = new CheckBox("direct");
    private final CheckBox benchChunked = new CheckBox("chunked");
    private final CheckBox benchConnect = new CheckBox("connect");
    private final CheckBox benchEcho = new CheckBox("echo");
    private final CheckBox benchImage = new CheckBox("image");
    private final TextField benchIters = new TextField("5");
    private final TextField benchNetwork = new TextField("local");
    private final TextArea benchLog = new TextArea();
    private final TableView<BenchRow> benchTable = new TableView<>();
    private final BarChart<String, Number> benchChart = new BarChart<>(new CategoryAxis(), new NumberAxis());
    private final Button benchRun = new Button("Run matrix");
    private final Button benchExport = new Button("Export CSV...");
    private Node view;

    @Override
    public String title() {
        return "Benchmark";
    }

    @Override
    public Node view() {
        if (view == null) {
            view = build();
        }
        return view;
    }

    private Node build() {
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
                Ui.label("transport modes"), benchDirect, benchChunked, benchConnect,
                new Separator(),
                Ui.label("workloads"), benchEcho, benchImage,
                new Separator(),
                Ui.label("iterations"), benchIters,
                Ui.label("network label"), benchNetwork,
                benchRun, benchExport, hint);
        side.setPadding(new Insets(12));
        side.setPrefWidth(250);
        side.setStyle("-fx-background-color: #f9fafb; -fx-border-color: transparent #e5e7eb transparent transparent;");

        benchChart.setAnimated(false);
        benchChart.setLegendSide(javafx.geometry.Side.TOP);
        benchChart.setTitle("transport time per payload (ms, lower is better)");
        benchChart.setPrefHeight(240);
        benchChart.setCategoryGap(12);

        benchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        benchTable.getColumns().setAll(
                Ui.column("network", 70, BenchRow::network),
                Ui.column("mode", 70, BenchRow::mode),
                Ui.column("workload", 75, BenchRow::workload),
                Ui.column("payload", 80, r -> Bytes.human(r.payload())),
                Ui.column("total ms", 70, r -> String.valueOf(r.totalMs())),
                Ui.column("server ms", 70, r -> r.serverMs() < 0 ? "-" : String.valueOf(r.serverMs())),
                Ui.column("transfer ms", 80, r -> String.valueOf(r.transferMs())),
                Ui.column("MB/s", 60, r -> String.format(Locale.ROOT, "%.1f", r.mbps())),
                Ui.column("sha256", 70, r -> r.ok() ? "ok" : "MISMATCH"));
        VBox.setVgrow(benchTable, Priority.ALWAYS);

        benchLog.setEditable(false);
        benchLog.setPrefRowCount(6);
        benchLog.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");

        VBox chartBox = new VBox(benchChart);
        VBox.setVgrow(benchChart, Priority.ALWAYS);

        VBox main = new VBox(8, chartBox, benchTable, Ui.label("run log"), benchLog);
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
                        javafx.application.Platform.runLater(() -> benchLog.appendText("$ " + shown + "\n"));
                        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String text = line;
                                javafx.application.Platform.runLater(() -> benchLog.appendText(text + "\n"));
                            }
                        }
                        int code = process.waitFor();
                        if (code != 0) {
                            String note = "[exit " + code + "]\n";
                            javafx.application.Platform.runLater(() -> benchLog.appendText(note));
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
        Ui.daemon(task, "bench");
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
}
