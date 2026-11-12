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

/**
 * Tool: rotate, flip and crop.
 *
 * There is no new remote method behind this one: the geometry ops are extra
 * cases in the same transform call, so the protocol did not have to grow.
 */
final class TransformTool implements Tool {

    private final ImageSource source = new ImageSource();
    private final ComboBox<String> opBox = new ComboBox<>();
    private final Slider marginSlider = new Slider(0, 45, 10);
    private final Label marginValue = new Label("10%");
    private final Button runButton = new Button("Apply");

    private final ImageView beforeView = new ImageView();
    private final ImageView afterView = new ImageView();

    private final Label totalLabel = new Label("-");
    private final Label computeLabel = new Label("-");
    private final Label sizeLabel = new Label("-");
    private final Label shaLabel = new Label("-");
    private final Label statusLabel = new Label("");

    private ImageLab lab;
    private byte[] sourcePng;
    private String sourceSha;
    private long lastRoundTripMs;
    private Node view;

    @Override
    public String title() {
        return "Transform";
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
                    Ui.stat("result size", sizeLabel),
                    Ui.stat("integrity", shaLabel)));
            view = pane;
            source.onChanged((img, png) -> {
                sourcePng = png;
                sourceSha = Bytes.sha256Hex(png);
                beforeView.setImage(Ui.toFx(png));
                afterView.setImage(null);
                sizeLabel.setText(img.getWidth() + " x " + img.getHeight());
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

        opBox.getItems().addAll("rotate 90", "rotate 180", "rotate 270",
                "flip horizontal", "flip vertical", "crop");
        opBox.setValue("rotate 90");
        opBox.setMaxWidth(Double.MAX_VALUE);
        opBox.valueProperty().addListener((o, a, b) -> marginSlider.setDisable(!"crop".equals(b)));

        marginSlider.setDisable(true);
        marginSlider.valueProperty().addListener(
                (o, a, b) -> marginValue.setText(Math.round(b.doubleValue()) + "%"));
        HBox marginRow = new HBox(8, marginSlider, marginValue);
        HBox.setHgrow(marginSlider, Priority.ALWAYS);
        marginValue.setMinWidth(40);

        runButton.setMaxWidth(Double.MAX_VALUE);
        runButton.setDisable(true);
        runButton.setOnAction(e -> run());

        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: #b91c1c; -fx-font-size: 11px;");

        box.getChildren().addAll(source.controls(),
                new javafx.scene.control.Separator(),
                Ui.label("Operation"), opBox,
                Ui.label("Crop margin (both sides)"), marginRow,
                runButton, statusLabel);
        return box;
    }

    private FilterSpec spec() {
        String choice = opBox.getValue();
        Map<String, Double> params = new HashMap<>();
        String name = "rotate";
        if (choice.startsWith("rotate")) {
            params.put("degrees", Double.parseDouble(choice.split(" ")[1]));
        } else if (choice.startsWith("flip")) {
            name = "flip";
            params.put("horizontal", choice.endsWith("horizontal") ? 1.0 : 0.0);
        } else {
            name = "crop";
            params.put("margin", marginSlider.getValue() / 100.0);
        }
        return new FilterSpec(name, params);
    }

    private void run() {
        if (lab == null || sourcePng == null) {
            return;
        }
        FilterSpec spec = spec();
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
                sizeLabel.setText(r.width() + " x " + r.height());
                shaLabel.setText(requestOk && responseOk ? "ok (req + resp)" : "MISMATCH");
                runButton.setDisable(false);
            }

            @Override
            protected void failed() {
                totalLabel.setText("failed");
                computeLabel.setText("-");
                shaLabel.setText("see message");
                statusLabel.setText(String.valueOf(getException()));
                runButton.setDisable(false);
            }
        };
        Ui.daemon(task, "transform");
    }
}
