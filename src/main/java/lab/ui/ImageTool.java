package lab.ui;

import java.util.HashMap;
import java.util.Map;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import lab.common.Bytes;
import lab.common.FilterSpec;
import lab.common.ImageLab;
import lab.common.ImageResult;

/** Tool: apply one filter to one image through the RMI service. */
final class ImageTool implements Tool {

    private final ImageSource source = new ImageSource();
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
    private final Label statusLabel = new Label("");

    private ImageLab lab;
    private byte[] sourcePng;
    private String sourceSha;
    private long lastRoundTripMs;
    private Node view;

    @Override
    public String title() {
        return "Image filter";
    }

    @Override
    public Node view() {
        if (view == null) {
            BorderPane pane = new BorderPane();
            pane.setLeft(controls());
            pane.setCenter(imageArea());
            pane.setBottom(Ui.statsBar(
                    Ui.stat("round trip", totalLabel),
                    Ui.stat("server compute", computeLabel),
                    Ui.stat("transfer (approx)", transferLabel),
                    Ui.stat("bytes", bytesLabel),
                    Ui.stat("integrity", shaLabel)));
            view = pane;
            source.onChanged((img, png) -> {
                sourcePng = png;
                sourceSha = Bytes.sha256Hex(png);
                beforeView.setImage(Ui.toFx(png));
                afterView.setImage(null);
                bytesLabel.setText("source " + Bytes.human(png.length));
                shaLabel.setText("-");
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

        filterBox.getItems().addAll("grayscale", "posterize", "sobel", "blur", "resize");
        filterBox.setValue("grayscale");
        filterBox.setMaxWidth(Double.MAX_VALUE);
        filterBox.valueProperty().addListener((o, a, b) -> radiusSlider.setDisable(!"blur".equals(b)));

        radiusSlider.setShowTickMarks(false);
        radiusSlider.valueProperty().addListener(
                (o, a, b) -> radiusValue.setText(String.valueOf(Math.round(b.doubleValue()))));
        HBox radiusRow = new HBox(8, radiusSlider, radiusValue);
        HBox.setHgrow(radiusSlider, Priority.ALWAYS);
        radiusSlider.setDisable(true);
        radiusValue.setMinWidth(24);

        runButton.setMaxWidth(Double.MAX_VALUE);
        runButton.setDisable(true);
        runButton.setOnAction(e -> run());

        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: #b91c1c; -fx-font-size: 11px;");

        box.getChildren().addAll(source.controls(),
                new javafx.scene.control.Separator(),
                Ui.label("Filter"), filterBox,
                Ui.label("Blur radius"), radiusRow,
                runButton, statusLabel);
        return box;
    }

    private Node imageArea() {
        HBox row = new HBox(16, Ui.imagePane("before", beforeView), Ui.imagePane("after", afterView));
        row.setPadding(new javafx.geometry.Insets(12));
        row.setFillHeight(true);
        return row;
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
        statusLabel.setText("");
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
                afterView.setImage(Ui.toFx(r.png()));
                totalLabel.setText(lastRoundTripMs + " ms");
                computeLabel.setText(r.computeMillis() + " ms");
                transferLabel.setText(Math.max(0, lastRoundTripMs - r.computeMillis()) + " ms");
                bytesLabel.setText("up " + Bytes.human(payload.length) + ", down " + Bytes.human(r.png().length));
                shaLabel.setText(requestOk && responseOk ? "ok (req + resp)" : "MISMATCH");
                runButton.setDisable(false);
            }

            @Override
            protected void failed() {
                totalLabel.setText("failed");
                computeLabel.setText("-");
                transferLabel.setText("-");
                shaLabel.setText("see message");
                statusLabel.setText(String.valueOf(getException()));
                runButton.setDisable(false);
            }
        };
        Ui.daemon(task, "run");
    }
}
