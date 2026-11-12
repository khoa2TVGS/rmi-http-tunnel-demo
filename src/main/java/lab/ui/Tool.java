package lab.ui;

import javafx.scene.Node;
import lab.common.ImageLab;

/** One mini app in the shell. */
public interface Tool {

    String title();

    Node view();

    /** A remote service became available (or was replaced by a reconnect). */
    default void onConnected(ImageLab lab) {}

    /** The connection went away; anything holding a stub must drop it. */
    default void onDisconnected() {}
}
