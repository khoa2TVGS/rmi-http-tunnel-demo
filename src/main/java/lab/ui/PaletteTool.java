package lab.ui;

import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import lab.common.Bytes;
import lab.common.ImageLab;
import lab.common.PaletteResult;

/**
 * Tool: reduce an image to N colours.
 *
 * The server returns the palette as well as the picture, so the client can show
 * exactly which colours were kept. That is the difference between sending bytes
 * and sending an object.
 */
final class PaletteTool implements Tool {

    private final ImageSource source = new ImageSource();
    private final Slider colorsSlider = new Slider(2, 32, 8);
    private final Label colorsValue = new Label("8");
    private final Button runButton = new Button("Reduce");

    private final ImageView beforeView = new ImageView();
    private final ImageView afterView = new ImageView();
    private final HBox swatches = new HBox(4);

    private final Label totalLabel = new Label("-");
    private final Label computeLabel = new Label("-");
    private final Label keptLabel = new Label("-");
    private final Label sizeLabel = new Label("-");
    private final Label statusLabel = new Label("");

    private ImageLab lab;
    private byte[] sourcePng;
    private long lastRoundTripMs;
    private Node view;

    @Override
    public String title() {
        return "Palette";
    }

    @Override
    public Node view() {
        if (view == null) {
            BorderPane pane = new BorderPane();
            pane.setLeft(controls());
            HBox row = new HBox(16, Ui.imagePane("before", beforeView), Ui.imagePane("after", afterView));
            row.setPadding(new javafx.geometry.Insets(12));
            row.setFillHeight(true);
            pane.setCenter(row);
            pane.setBottom(Ui.statsBar(
                    Ui.stat("round trip", totalLabel),
                    Ui.stat("server compute", computeLabel),
                    Ui.stat("colours kept", keptLabel),
                    Ui.stat("result size", sizeLabel)));
            view = pane;
            source.onChanged((img, png) -> {
                sourcePng = png;
                beforeView.setImage(Ui.toFx(png));
                afterView.setImage(null);
                swatches.getChildren().clear();
                keptLabel.setText("-");
                sizeLabel.setText(img.getWidth() + " x " + img.getHeight());
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

        colorsSlider.setShowTickMarks(true);
        colorsSlider.setMajorTickUnit(10);
        colorsSlider.setMinorTickCount(0);
        colorsSlider.setSnapToTicks(false);
        colorsSlider.valueProperty().addListener((o, a, b) -> colorsValue.setText(String.valueOf(Math.round(b.doubleValue()))));
        HBox colorsRow = new HBox(8, colorsSlider, colorsValue);
        HBox.setHgrow(colorsSlider, Priority.ALWAYS);
        colorsValue.setMinWidth(24);

        runButton.setMaxWidth(Double.MAX_VALUE);
        runButton.setDisable(true);
        runButton.setOnAction(e -> run());

        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: #b91c1c; -fx-font-size: 11px;");

        box.getChildren().addAll(source.controls(),
                new javafx.scene.control.Separator(),
                Ui.label("Target colours (k-means)"), colorsRow,
                Ui.label("Kept palette"), swatches,
                runButton, statusLabel);
        return box;
    }

    private void run() {
        if (lab == null || sourcePng == null) {
            return;
        }
        byte[] payload = sourcePng;
        int colors = (int) Math.round(colorsSlider.getValue());
        runButton.setDisable(true);
        statusLabel.setText("");
        totalLabel.setText("running ...");

        Task<PaletteResult> task = new Task<>() {
            @Override
            protected PaletteResult call() throws Exception {
                long t0 = System.nanoTime();
                PaletteResult r = lab.quantize(payload, colors);
                lastRoundTripMs = (System.nanoTime() - t0) / 1_000_000L;
                return r;
            }

            @Override
            protected void succeeded() {
                PaletteResult r = getValue();
                afterView.setImage(Ui.toFx(r.png()));
                swatches.getChildren().setAll(swatchNodes(r.colors()));
                totalLabel.setText(lastRoundTripMs + " ms");
                computeLabel.setText(r.computeMillis() + " ms");
                keptLabel.setText(r.colors().length + " of " + colors);
                sizeLabel.setText(r.width() + " x " + r.height() + ", " + Bytes.human(r.png().length));
                runButton.setDisable(false);
            }

            @Override
            protected void failed() {
                totalLabel.setText("failed");
                statusLabel.setText(String.valueOf(getException()));
                runButton.setDisable(false);
            }
        };
        Ui.daemon(task, "palette");
    }

    private static Node[] swatchNodes(int[] colors) {
        Node[] nodes = new Node[colors.length];
        for (int i = 0; i < colors.length; i++) {
            int c = colors[i];
            int r = (c >> 16) & 0xff;
            int g = (c >> 8) & 0xff;
            int b = c & 0xff;
            Rectangle rect = new Rectangle(16, 16, Color.rgb(r, g, b));
            rect.setStroke(Color.web("#d1d5db"));
            Tooltip.install(rect, new Tooltip(String.format("#%02x%02x%02x", r, g, b)));
            nodes[i] = rect;
        }
        return nodes;
    }
}
