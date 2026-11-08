package lab.ui;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import lab.common.Histogram;
import lab.common.ImageLab;

/**
 * Tool: ask the server for the distribution of an image.
 *
 * The point of a separate tab is that the reply is a small structured object,
 * not a picture: 4 x 256 ints travel back where megabytes went out, which is
 * the other half of what serialisation over a tunnel is for.
 */
final class HistogramTool implements Tool {

    private final ImageSource source = new ImageSource();
    private final Button runButton = new Button("Analyse");
    private final Canvas canvas = new Canvas(600, 300);

    private final Label totalLabel = new Label("-");
    private final Label pixelsLabel = new Label("-");
    private final Label meanLabel = new Label("-");
    private final Label peakLabel = new Label("-");
    private final Label statusLabel = new Label("");

    private ImageLab lab;
    private byte[] sourcePng;
    private Histogram hist;
    private long lastRoundTripMs;
    private Node view;

    @Override
    public String title() {
        return "Histogram";
    }

    @Override
    public Node view() {
        if (view == null) {
            BorderPane pane = new BorderPane();
            pane.setLeft(controls());
            pane.setCenter(plot());
            pane.setBottom(Ui.statsBar(
                    Ui.stat("round trip", totalLabel),
                    Ui.stat("pixels", pixelsLabel),
                    Ui.stat("mean luma", meanLabel),
                    Ui.stat("peak bin", peakLabel)));
            view = pane;
            source.onChanged((img, png) -> {
                sourcePng = png;
                hist = null;
                draw();
                pixelsLabel.setText(String.valueOf((long) img.getWidth() * img.getHeight()));
                meanLabel.setText("-");
                peakLabel.setText("-");
                statusLabel.setText("");
                runButton.setDisable(lab == null);
            });
            source.regenerate();
        }
        return view;
    }

    @Override
    public void onConnected(ImageLab remote) {
        lab = remote;
        runButton.setDisable(sourcePng == null);
    }

    @Override
    public void onDisconnected() {
        lab = null;
        runButton.setDisable(true);
        statusLabel.setText("disconnected");
    }

    private Node controls() {
        VBox box = Ui.sideBar();
        runButton.setMaxWidth(Double.MAX_VALUE);
        runButton.setDisable(true);
        runButton.setOnAction(e -> run());
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: #b91c1c; -fx-font-size: 11px;");

        HBox legend = new HBox(12,
                swatch("#dc2626", "red"), swatch("#16a34a", "green"),
                swatch("#2563eb", "blue"), swatch("#9ca3af", "luma"));

        box.getChildren().addAll(source.controls(),
                new javafx.scene.control.Separator(),
                Ui.label("Channels"), legend, runButton, statusLabel);
        return box;
    }

    private Node plot() {
        StackPane holder = new StackPane(canvas);
        holder.setPadding(new Insets(12));
        holder.setStyle("-fx-background-color: #ffffff;");
        // An unmanaged Canvas does not feed the StackPane's preferred size. Without
        // this the bound width becomes the holder's minimum, so shrinking the window
        // clipped the plot instead of reflowing it (and the canvas never redrew).
        canvas.setManaged(false);
        canvas.relocate(12, 12);
        canvas.widthProperty().bind(holder.widthProperty().subtract(24));
        canvas.heightProperty().bind(holder.heightProperty().subtract(24));
        canvas.widthProperty().addListener((o, a, b) -> draw());
        canvas.heightProperty().addListener((o, a, b) -> draw());
        VBox box = new VBox(6, Ui.label("counts per 8-bit value, each channel scaled to its own peak"), holder);
        VBox.setVgrow(holder, Priority.ALWAYS);
        return box;
    }

    private static Node swatch(String color, String name) {
        javafx.scene.shape.Rectangle r = new javafx.scene.shape.Rectangle(10, 10, Color.web(color));
        return new HBox(4, r, Ui.label(name));
    }

    private void run() {
        if (lab == null || sourcePng == null) {
            return;
        }
        byte[] payload = sourcePng;
        runButton.setDisable(true);
        statusLabel.setText("");
        totalLabel.setText("running ...");

        Task<Histogram> task = new Task<>() {
            @Override
            protected Histogram call() throws Exception {
                long t0 = System.nanoTime();
                Histogram h = lab.histogram(payload);
                lastRoundTripMs = (System.nanoTime() - t0) / 1_000_000L;
                return h;
            }

            @Override
            protected void succeeded() {
                hist = getValue();
                draw();
                totalLabel.setText(lastRoundTripMs + " ms");
                pixelsLabel.setText(String.valueOf(hist.pixels()));
                meanLabel.setText(String.format(java.util.Locale.ROOT, "%.1f", hist.meanLuma()));
                peakLabel.setText(String.valueOf(hist.peakLuma()));
                runButton.setDisable(false);
            }

            @Override
            protected void failed() {
                totalLabel.setText("failed");
                statusLabel.setText(String.valueOf(getException()));
                runButton.setDisable(false);
            }
        };
        Ui.daemon(task, "histogram");
    }

    private void draw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 1 || h <= 1) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.web("#e5e7eb"));
        g.setLineWidth(1);
        for (int i = 1; i < 4; i++) {
            g.strokeLine(0, h * i / 4, w, h * i / 4);
        }
        if (hist == null) {
            g.setFill(Color.web("#9ca3af"));
            g.fillText("run Analyse to see the distribution", 16, h / 2);
            return;
        }
        area(g, hist.luma(), Color.web("#111827", 0.20), w, h);
        curve(g, hist.red(), Color.web("#dc2626"), w, h);
        curve(g, hist.green(), Color.web("#16a34a"), w, h);
        curve(g, hist.blue(), Color.web("#2563eb"), w, h);
        g.setFill(Color.web("#9ca3af"));
        g.fillText("0", 4, h - 4);
        g.fillText("255", w - 22, h - 4);
    }

    private static double top(int[] bins, double value, double h) {
        return h - Math.min(1.0, value / Math.max(1.0, peak(bins))) * (h - 8) - 4;
    }

    private static double peak(int[] bins) {
        double max = 0;
        for (int b : bins) {
            max = Math.max(max, b);
        }
        return max;
    }

    private static void curve(GraphicsContext g, int[] bins, Color color, double w, double h) {
        g.setStroke(color);
        g.setLineWidth(1.3);
        g.beginPath();
        for (int i = 0; i < bins.length; i++) {
            double x = i * w / (bins.length - 1);
            double y = top(bins, bins[i], h);
            if (i == 0) {
                g.moveTo(x, y);
            } else {
                g.lineTo(x, y);
            }
        }
        g.stroke();
    }

    private static void area(GraphicsContext g, int[] bins, Color color, double w, double h) {
        g.setFill(color);
        g.beginPath();
        g.moveTo(0, h);
        for (int i = 0; i < bins.length; i++) {
            double x = i * w / (bins.length - 1);
            g.lineTo(x, top(bins, bins[i], h));
        }
        g.lineTo(w, h);
        g.closePath();
        g.fill();
    }
}
