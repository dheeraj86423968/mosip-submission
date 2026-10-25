package io.mosip.vivid.desktop.ui;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.desktop.LivenessMessages;
import io.mosip.vivid.desktop.LivenessUiBinding;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.NodeOrientation;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JavaFX implementation of {@link LivenessUiBinding}: an overlay stacked on the client's preview {@code ImageView}.
 * Every binding method is safe to call from any thread; rendering is marshalled with {@link Platform#runLater}.
 * Text comes only from {@code i18n/liveness_<lang>.properties} through {@link LivenessMessages} keys.
 * <p>
 * Not a Spring bean: one instance per overlay, created by {@link #load(Locale)}.
 */
public class LivenessOverlayController implements LivenessUiBinding {
    private static final String FXML = "/fxml/LivenessOverlay.fxml";

    @FXML private StackPane overlayRoot;
    @FXML private ImageView previewImage;
    @FXML private Ellipse faceGuide;
    @FXML private Label stateBadge, deviceChip, networkChip, retryLabel, hintLabel;
    @FXML private VBox challengeCard, failurePane, lockedPane, diagnosticsPane;
    @FXML private Label challengeIcon, challengeText, challengeStep, failureText, failureGuidance, lockedTitle, lockedGuidance, diagnosticsTitle;
    @FXML private HBox progressDots;
    @FXML private Button cancelButton;
    @FXML private Hyperlink diagnosticsLink;
    @FXML private TextArea diagnosticsText;

    private ResourceBundle bundle;
    private Locale locale = Locale.ENGLISH;
    private Runnable onCancel = () -> {};
    private ImageView externalPreview;
    private final AtomicBoolean renderPending = new AtomicBoolean();
    private final AtomicReference<FaceFrame> latestFrame = new AtomicReference<>();
    private volatile boolean diagnosticsAllowed;

    /** Loads the FXML and returns the controller; call {@link #root()} to stack it onto the preview pane. */
    public static LivenessOverlayController load(Locale locale) throws IOException {
        URL url = Objects.requireNonNull(LivenessOverlayController.class.getResource(FXML), FXML + " missing");
        FXMLLoader loader = new FXMLLoader(url);
        loader.load();
        LivenessOverlayController c = loader.getController();
        c.setLocale(locale);
        return c;
    }

    public Parent root() { return overlayRoot; }

    @FXML private void initialize() {
        setLocale(Locale.ENGLISH);
        reset();
    }

    public void setLocale(Locale locale) {
        this.locale = locale == null ? Locale.ENGLISH : locale;
        try { bundle = ResourceBundle.getBundle(LivenessMessages.BUNDLE, this.locale); }
        catch (MissingResourceException e) { bundle = ResourceBundle.getBundle(LivenessMessages.BUNDLE, Locale.ENGLISH); }
        boolean rtl = "ar".equals(this.locale.getLanguage()) || "he".equals(this.locale.getLanguage()) || "fa".equals(this.locale.getLanguage()) || "ur".equals(this.locale.getLanguage());
        fx(() -> {
            overlayRoot.setNodeOrientation(rtl ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
            cancelButton.setText(text(LivenessMessages.CANCEL));
            diagnosticsLink.setText(text(LivenessMessages.DIAGNOSTICS_TOGGLE));
            diagnosticsTitle.setText(text(LivenessMessages.DIAGNOSTICS_TITLE));
            lockedTitle.setText(text(LivenessMessages.LOCKED_TITLE));
            lockedGuidance.setText(text(LivenessMessages.LOCKED_GUIDANCE));
            failureGuidance.setText(text(LivenessMessages.FAILURE_GUIDANCE));
        });
    }

    /** Render frames into the host's existing ImageView (e.g. {@code biometricImage}) instead of the overlay's own. */
    public void setPreviewTarget(ImageView target) {
        this.externalPreview = target;
        fx(() -> { previewImage.setVisible(target == null); previewImage.setManaged(target == null); });
    }

    public void setOnCancel(Runnable r) { this.onCancel = r == null ? () -> {} : r; }
    public void setCancelVisible(boolean visible) { fx(() -> { cancelButton.setVisible(visible); cancelButton.setManaged(visible); }); }

    /** Diagnostics link only exists when policy enables diagnostic mode. */
    public void setDiagnosticsAllowed(boolean allowed) {
        this.diagnosticsAllowed = allowed;
        fx(() -> { diagnosticsLink.setVisible(allowed); diagnosticsLink.setManaged(allowed); if (!allowed) { diagnosticsPane.setVisible(false); diagnosticsPane.setManaged(false); } });
    }

    public void setHighContrast(boolean on) {
        fx(() -> { if (on && !overlayRoot.getStyleClass().contains("high-contrast")) overlayRoot.getStyleClass().add("high-contrast"); if (!on) overlayRoot.getStyleClass().remove("high-contrast"); });
    }

    public void setOnline(boolean online) {
        fx(() -> networkChip.setText(text(online ? LivenessMessages.ONLINE : LivenessMessages.OFFLINE)));
    }

    public void reset() {
        fx(() -> {
            guide("guide-idle");
            stateBadge.setText(text(LivenessMessages.keyForPhase(SessionState.Phase.IDLE)));
            hintLabel.setText(""); hintLabel.setVisible(false);
            hide(challengeCard); hide(failurePane); hide(lockedPane); hide(diagnosticsPane);
            progressDots.getChildren().clear();
            retryLabel.setText(""); retryLabel.setVisible(false);
            deviceChip.setText(""); deviceChip.setVisible(false);
            diagnosticsText.clear();
        });
    }

    // ---- LivenessUiBinding ---------------------------------------------------------------------------------------

    @Override public void showState(SessionState state) {
        fx(() -> {
            String cls;
            switch (state.phase()) {
                case DEVICE_CONNECTING: cls = "guide-device"; break;
                case FACE_ACQUIRING: cls = "guide-acquiring"; break;
                case PASSIVE_EVALUATING: cls = "guide-evaluating"; break;
                case ACTIVE_CHALLENGE: cls = "guide-challenge"; break;
                case PASSED: cls = "guide-passed"; break;
                case FAILED: cls = "guide-failed"; break;
                case LOCKED: cls = "guide-locked"; break;
                default: cls = "guide-idle";
            }
            guide(cls);
            stateBadge.setText(text(LivenessMessages.keyForPhase(state.phase())));
            if (state.phase() != SessionState.Phase.ACTIVE_CHALLENGE) hide(challengeCard);
            if (state.phase() != SessionState.Phase.FAILED && state.phase() != SessionState.Phase.LOCKED) hide(failurePane);
            if (state.phase() == SessionState.Phase.PASSED) { hide(failurePane); hide(lockedPane); }
            if (state.maxAttempts() > 1 && state.attempt() > 0) {
                retryLabel.setText(MessageFormat.format(text(LivenessMessages.RETRY_COUNTER), state.attempt(), state.maxAttempts()));
                retryLabel.setVisible(true);
            }
            state.challenge().ifPresent(c -> dots(c.index(), c.total(), state.phase() == SessionState.Phase.ACTIVE_CHALLENGE));
        });
    }

    @Override public void showChallenge(Challenge challenge) {
        fx(() -> {
            challengeIcon.setText(icon(challenge.type()));
            challengeIcon.getStyleClass().removeIf(s -> s.startsWith("icon-"));
            challengeIcon.getStyleClass().add("icon-" + challenge.type().name().toLowerCase(Locale.ROOT));
            challengeText.setText(text(LivenessMessages.keyForChallenge(challenge.type())));
            challengeStep.setText(MessageFormat.format(text(LivenessMessages.CHALLENGE_PROGRESS), challenge.index() + 1, challenge.total()));
            show(challengeCard);
            hide(failurePane);
            dots(challenge.index(), challenge.total(), true);
        });
    }

    @Override public void showHint(SessionState.Hint hint) {
        fx(() -> {
            String t = hint == SessionState.Hint.NONE ? "" : text(LivenessMessages.keyForHint(hint));
            hintLabel.setText(t);
            hintLabel.setVisible(!t.isEmpty());
        });
    }

    @Override public void showFailure(FailureReason reason, String messageKey) {
        // messageKey is already generic for security-sensitive reasons; never consult `reason` for text.
        fx(() -> {
            failureText.setText(text(messageKey == null ? LivenessMessages.GENERIC_FAILURE : messageKey));
            failureGuidance.setVisible(reason != FailureReason.CANCELLED && reason != FailureReason.POLICY_DISABLED);
            show(failurePane);
            hide(challengeCard);
            guide("guide-failed");
        });
    }

    @Override public void showRetry(int attempt, int maxAttempts) {
        fx(() -> {
            retryLabel.setText(MessageFormat.format(text(LivenessMessages.RETRY_COUNTER), attempt, maxAttempts));
            retryLabel.setVisible(true);
            hintLabel.setText(text(LivenessMessages.keyForHint(SessionState.Hint.RETRY_AVAILABLE)));
            hintLabel.setVisible(true);
        });
    }

    @Override public void showLocked() {
        fx(() -> { hide(challengeCard); hide(failurePane); show(lockedPane); guide("guide-locked"); stateBadge.setText(text(LivenessMessages.keyForPhase(SessionState.Phase.LOCKED))); });
    }

    @Override public void showPassed() {
        fx(() -> { hide(challengeCard); hide(failurePane); hide(lockedPane); guide("guide-passed"); hintLabel.setText(text(LivenessMessages.CAPTURED_OK)); hintLabel.setVisible(true); });
    }

    @Override public void showFrame(FaceFrame frame) {
        latestFrame.set(frame);
        if (renderPending.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                renderPending.set(false);
                FaceFrame f = latestFrame.getAndSet(null);
                if (f == null) return;
                Image img = toImage(f);
                if (img == null) return;
                ImageView target = externalPreview != null ? externalPreview : previewImage;
                target.setImage(img);
            });
        }
    }

    @Override public void showDeviceHealth(DeviceHealth health) {
        fx(() -> {
            deviceChip.setText(text(LivenessMessages.keyForDevice(health.state())));
            deviceChip.setVisible(true);
            deviceChip.getStyleClass().removeAll("device-ok", "device-warn", "device-bad");
            switch (health.state()) {
                case CONNECTED: deviceChip.getStyleClass().add("device-ok"); break;
                case DEGRADED: case RECONNECTING: case CONNECTING: deviceChip.getStyleClass().add("device-warn"); break;
                default: deviceChip.getStyleClass().add("device-bad");
            }
        });
    }

    @Override public void showDiagnostics(Map<String, String> values) {
        if (!diagnosticsAllowed) return;
        StringBuilder sb = new StringBuilder();
        values.forEach((k, v) -> sb.append(k).append(" = ").append(v).append('\n'));
        fx(() -> diagnosticsText.setText(sb.toString()));
    }

    // ---- FXML handlers --------------------------------------------------------------------------------------------

    @FXML private void onCancel() { onCancel.run(); }

    @FXML private void onToggleDiagnostics() {
        if (!diagnosticsAllowed) return;
        boolean v = !diagnosticsPane.isVisible();
        diagnosticsPane.setVisible(v); diagnosticsPane.setManaged(v);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private String text(String key) {
        if (key == null) return "";
        try { return bundle.getString(key); } catch (MissingResourceException e) { return key; }
    }

    private void guide(String cls) {
        faceGuide.getStyleClass().removeIf(s -> s.startsWith("guide-"));
        faceGuide.getStyleClass().add(cls);
    }

    private void dots(int index, int total, boolean active) {
        progressDots.getChildren().clear();
        for (int i = 0; i < total; i++) {
            Circle c = new Circle(6);
            c.getStyleClass().add("dot");
            if (i < index) c.getStyleClass().add("dot-done");
            else if (i == index && active) c.getStyleClass().add("dot-active");
            progressDots.getChildren().add(c);
        }
    }

    private static void show(javafx.scene.Node n) { n.setVisible(true); n.setManaged(true); }
    private static void hide(javafx.scene.Node n) { n.setVisible(false); n.setManaged(false); }

    private static void fx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    /** Placeholder glyphs; swap for animated icons via the icon-&lt;type&gt; style class. */
    static String icon(ChallengeType type) {
        switch (type) {
            case BLINK: case BLINK_TWICE: return "◉";
            case SMILE: return "☺";
            case OPEN_MOUTH: return "○";
            case RAISE_EYEBROWS: return "⌒";
            case TURN_LEFT: return "←";
            case TURN_RIGHT: return "→";
            case LOOK_UP: return "↑";
            case LOOK_DOWN: return "↓";
            case NOD: return "↕";
            case HOLD_STILL: default: return "●";
        }
    }

    static Image toImage(FaceFrame f) {
        ByteBuffer px = f.pixels();
        switch (f.format()) {
            case MJPEG: {
                byte[] bytes = new byte[px.remaining()];
                px.get(bytes);
                return new Image(new ByteArrayInputStream(bytes));
            }
            case RGB888: case BGR888: {
                WritableImage img = new WritableImage(f.width(), f.height());
                PixelWriter w = img.getPixelWriter();
                byte[] row = new byte[f.width() * 3];
                boolean bgr = f.format() == PixelFormat.BGR888;
                for (int y = 0; y < f.height(); y++) {
                    px.get(row);
                    for (int x = 0; x < f.width(); x++) {
                        int r = row[x * 3 + (bgr ? 2 : 0)] & 0xFF, g = row[x * 3 + 1] & 0xFF, b = row[x * 3 + (bgr ? 0 : 2)] & 0xFF;
                        w.setArgb(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
                    }
                }
                return img;
            }
            case GRAY8: {
                WritableImage img = new WritableImage(f.width(), f.height());
                PixelWriter w = img.getPixelWriter();
                for (int y = 0; y < f.height(); y++) for (int x = 0; x < f.width(); x++) { int v = px.get() & 0xFF; w.setArgb(x, y, 0xFF000000 | (v << 16) | (v << 8) | v); }
                return img;
            }
            default: return null; // NV21/YUV420 previews are an Android concern
        }
    }
}
