package lab.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.function.Function;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import lab.common.SynthImage;

/** The bits of JavaFX boilerplate that every tool needs. */
final class Ui {
    private Ui() {}

    static Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: #6b7280;");
        return l;
    }

    static Node stat(String caption, Label value) {
        VBox v = new VBox(2, label(caption), value);
        value.setStyle("-fx-font-size: 13px; -fx-text-fill: #111827;");
        return v;
    }

    static <T> TableColumn<T, String> column(String title, double width, Function<T, String> getter) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(getter.apply(data.getValue())));
        return c;
    }

    static Image toFx(BufferedImage img) {
        return new Image(new ByteArrayInputStream(SynthImage.toPng(img)));
    }

    static Image toFx(byte[] png) {
        return new Image(new ByteArrayInputStream(png));
    }

    /** A titled pane that scales its picture to whatever room the layout gives it. */
    static Node imagePane(String title, ImageView view) {
        view.setPreserveRatio(true);
        StackPane holder = new StackPane(view);
        holder.setMinSize(240, 200);
        holder.setStyle("-fx-background-color: #ffffff; -fx-border-color: #d1d5db;");
        view.fitWidthProperty().bind(holder.widthProperty().subtract(16));
        view.fitHeightProperty().bind(holder.heightProperty().subtract(16));

        VBox box = new VBox(6, label(title), holder);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        VBox.setVgrow(holder, Priority.ALWAYS);
        return box;
    }

    /** The 300px control column every tool puts on its left. */
    static VBox sideBar() {
        VBox box = new javafx.scene.layout.VBox(10);
        box.setPadding(new javafx.geometry.Insets(12));
        box.setPrefWidth(300);
        box.setStyle("-fx-background-color: #f9fafb; -fx-border-color: transparent #e5e7eb transparent transparent;");
        return box;
    }

    /** The stats strip every tool puts along its bottom edge. */
    static Node statsBar(Node... items) {
        HBox bar = new HBox(28, items);
        bar.setPadding(new javafx.geometry.Insets(10, 12, 12, 12));
        bar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e7eb transparent transparent transparent;");
        return bar;
    }

    static void daemon(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
