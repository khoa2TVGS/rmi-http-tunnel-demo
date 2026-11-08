package lab.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import lab.common.Bytes;
import lab.common.SynthImage;

/**
 * The image every image tool starts from: a generated sample or a file the user
 * picks. Shared so each tool does not grow its own copy of the same controls,
 * and so every tool sends the tool the exact same PNG for the same settings --
 * which is what makes numbers from two tools comparable.
 */
final class ImageSource {

    interface Listener {
        void changed(BufferedImage image, byte[] png);
    }

    private final ToggleGroup group = new ToggleGroup();
    private final CheckBox noise = new CheckBox("add noise");
    private final Button open = new Button("Open image...");
    private final Label summary = new Label();
    private final VBox box = new VBox(6);

    private Listener listener;
    private BufferedImage image;
    private byte[] png;
    private int generation;

    ImageSource() {
        VBox samples = new VBox(4);
        for (int size : new int[] {512, 1024, 2048, 4096}) {
            RadioButton rb = new RadioButton(size + " x " + size);
            rb.setToggleGroup(group);
            rb.setUserData(size);
            rb.setSelected(size == 1024);
            samples.getChildren().add(rb);
        }
        group.selectedToggleProperty().addListener((o, a, b) -> regenerate());
        noise.selectedProperty().addListener((o, a, b) -> regenerate());

        open.setMaxWidth(Double.MAX_VALUE);
        open.setOnAction(e -> pickFile());
        summary.setStyle("-fx-font-size: 11px; -fx-text-fill: #6b7280;");
        summary.setWrapText(true);

        box.getChildren().addAll(Ui.label("Sample"), samples, noise, open, summary);
    }

    Node controls() {
        return box;
    }

    void onChanged(Listener l) {
        listener = l;
    }

    BufferedImage image() {
        return image;
    }

    byte[] png() {
        return png;
    }

    void regenerate() {
        int size = selectedSize();
        boolean noisy = noise.isSelected();
        int mine = ++generation;
        Task<Void> task = new Task<>() {
            private BufferedImage img;
            private byte[] bytes;

            @Override
            protected Void call() {
                img = SynthImage.generate(size, noisy);
                bytes = SynthImage.toPng(img);
                return null;
            }

            @Override
            protected void succeeded() {
                if (mine == generation) {
                    publish(img, bytes);
                }
            }
        };
        Ui.daemon(task, "source");
    }

    private void load(File file) {
        int mine = ++generation;
        Task<Void> task = new Task<>() {
            private BufferedImage img;
            private byte[] bytes;

            @Override
            protected Void call() throws Exception {
                img = SynthImage.fromPng(Files.readAllBytes(file.toPath()));
                bytes = SynthImage.toPng(img);
                return null;
            }

            @Override
            protected void succeeded() {
                if (mine == generation) {
                    publish(img, bytes);
                }
            }

            @Override
            protected void failed() {
                summary.setText("open failed: " + getException().getMessage());
            }
        };
        Ui.daemon(task, "open");
    }

    private void pickFile() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
        File file = chooser.showOpenDialog(null);
        if (file != null) {
            load(file);
        }
    }

    private void publish(BufferedImage img, byte[] bytes) {
        image = img;
        png = bytes;
        summary.setText(img.getWidth() + " x " + img.getHeight() + ", " + Bytes.human(bytes.length));
        if (listener != null) {
            listener.changed(img, bytes);
        }
    }

    private int selectedSize() {
        Toggle t = group.getSelectedToggle();
        return t == null ? 1024 : (Integer) t.getUserData();
    }
}
