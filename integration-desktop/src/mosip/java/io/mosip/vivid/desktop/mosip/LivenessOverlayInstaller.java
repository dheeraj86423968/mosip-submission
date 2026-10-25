package io.mosip.vivid.desktop.mosip;

import io.mosip.registration.context.ApplicationContext;
import io.mosip.vivid.desktop.DesktopLivenessConfig;
import io.mosip.vivid.desktop.ui.LivenessOverlayController;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps an existing preview {@code ImageView} (GenericBiometricFXML.fxml {@code biometricImage},
 * OperatorAuthentication.fxml / RegistrationLogin.fxml {@code faceImage}) in a {@code StackPane} at runtime and
 * stacks the liveness overlay on it, so no client FXML has to be edited. Call from any thread; blocks until the
 * FX thread has done the work.
 */
public final class LivenessOverlayInstaller {
    private LivenessOverlayInstaller() {}

    public static LivenessOverlayController install(ImageView preview, DesktopLivenessConfig config) throws IOException {
        AtomicReference<LivenessOverlayController> out = new AtomicReference<>();
        AtomicReference<IOException> err = new AtomicReference<>();
        Runnable work = () -> {
            try {
                Locale locale = config.locale();
                LivenessOverlayController c = LivenessOverlayController.load(locale);
                c.setPreviewTarget(preview);
                c.setDiagnosticsAllowed(config.diagnosticsEnabled());
                c.setHighContrast(config.highContrast());
                Pane parent = (Pane) preview.getParent();
                if (!(parent instanceof StackPane) || !parent.getStyleClass().contains("liveness-host")) {
                    StackPane host = new StackPane();
                    host.getStyleClass().add("liveness-host");
                    int idx = parent.getChildren().indexOf(preview);
                    parent.getChildren().remove(preview);
                    host.getChildren().add(preview);
                    copyGridConstraints(preview, host);
                    parent.getChildren().add(idx, host);
                    parent = host;
                } else {
                    parent.getChildren().removeIf(n -> n.getStyleClass().contains("liveness-overlay"));
                }
                parent.getChildren().add(c.root());
                out.set(c);
            } catch (IOException e) { err.set(e); }
        };
        if (Platform.isFxApplicationThread()) work.run();
        else {
            CountDownLatch done = new CountDownLatch(1);
            Platform.runLater(() -> { try { work.run(); } finally { done.countDown(); } });
            try { done.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (err.get() != null) throw err.get();
        return out.get();
    }

    /** Remove the overlay again (capture finished); keeps the host StackPane. */
    public static void remove(LivenessOverlayController c) {
        if (c == null) return;
        Platform.runLater(() -> { Node root = c.root(); if (root.getParent() instanceof Pane) ((Pane) root.getParent()).getChildren().remove(root); });
    }

    private static void copyGridConstraints(Node from, Node to) {
        javafx.scene.layout.GridPane.setColumnIndex(to, javafx.scene.layout.GridPane.getColumnIndex(from));
        javafx.scene.layout.GridPane.setRowIndex(to, javafx.scene.layout.GridPane.getRowIndex(from));
        javafx.scene.layout.GridPane.setHalignment(to, javafx.scene.layout.GridPane.getHalignment(from));
        javafx.scene.layout.GridPane.setValignment(to, javafx.scene.layout.GridPane.getValignment(from));
        javafx.scene.layout.AnchorPane.setTopAnchor(to, javafx.scene.layout.AnchorPane.getTopAnchor(from));
        javafx.scene.layout.AnchorPane.setLeftAnchor(to, javafx.scene.layout.AnchorPane.getLeftAnchor(from));
    }

    static Locale currentLocale() { return DesktopLivenessConfig.fromApplicationMap(ApplicationContext.map()).locale(); }
}
