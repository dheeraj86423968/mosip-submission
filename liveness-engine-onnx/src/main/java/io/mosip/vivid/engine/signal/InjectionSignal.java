package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Best-effort, desktop-only injection check: is a virtual camera driver installed on a machine whose frame source
 * claims to be a physical system camera? Virtual cameras (v4l2loopback, OBS Virtual Camera, ManyCam, ...) are the
 * standard tool for feeding a pre-recorded or deepfaked stream into an application.
 *
 * <ul>
 *   <li>Linux: reads {@code /sys/class/video4linux/video*}/{@code name}, and also inspects where each device sits in
 *       sysfs, because a virtual camera can be given any name ({@code v4l2loopback card_label="Integrated Camera"}):
 *       a device whose driver is {@code v4l2loopback}, or with no USB or PCI ancestor, is reported as
 *       {@code "<name> [no physical bus]"}; a USB device whose vendor:product is a known HDMI/USB capture dongle
 *       (the usual way to replay a laptop screen as a "camera") is reported as {@code "<name> [usb capture card vvvv:pppp]"}.
 *       Both markers are in {@link #KNOWN_VIRTUAL_CAMERAS}.</li>
 *   <li>Windows: DirectShow enumeration needs COM, which this pure-Java module does not bind; {@link #windowsDeviceNames()}
 *       is a documented stub that returns nothing. A host integration (the JavaFX Registration Client already enumerates
 *       webcams) can pass the device names in through {@link #InjectionSignal(boolean, Supplier)}.</li>
 * </ul>
 * Decision: when {@code sourceIsSystemCamera} is set and a known virtual-camera name is present, the signal emits a
 * hard veto with attack class {@code INJECTION}. Otherwise it reports a low-confidence neutral score. This is a
 * heuristic: it cannot tell which device the source actually opened, so the veto policy is deliberately conservative
 * (registration stations are managed machines where virtual cameras should not be installed at all). Disable it
 * through {@code vivid.engine.signals} if that assumption does not hold. SBI devices that sign frames make this check
 * unnecessary.
 */
public final class InjectionSignal implements Signal {
    private static final Logger LOG = LoggerFactory.getLogger(InjectionSignal.class);

    /** Lower-case substrings of known virtual camera driver / filter names. */
    public static final List<String> KNOWN_VIRTUAL_CAMERAS = List.of(
            "v4l2loopback", "v4l2 loopback", "dummy video device", "obs virtual camera", "obs-camera", "obs cam",
            "manycam", "splitcam", "xsplit", "snap camera", "droidcam", "iriun", "epoccam", "e2esoft", "vcam",
            "virtual camera", "virtualcam", "akvcam", "unity video capture", "newtek ndi", "camtwist", "youcam", "fake webcam",
            "[no physical bus]", "[usb capture card");

    /**
     * USB vendor:product ids of common HDMI-to-USB capture dongles and capture cards (UVC devices that turn any video
     * output, e.g. a laptop playing a recording, into a "webcam"). Lower-case hex. Not exhaustive.
     */
    public static final List<String> KNOWN_CAPTURE_CARDS = List.of(
            "534d:2109", "534d:2130", "345f:2130", "345f:2131",     // MacroSilicon MS2109 / MS2130 generic dongles
            "1164:7022", "1164:f5a2",                               // YUAN High-Tech capture cards
            "0fd9:0066", "0fd9:007b", "0fd9:006e", "0fd9:0079",     // Elgato Cam Link / HD60 S
            "07ca:0553", "07ca:0570", "07ca:2553",                  // AVerMedia Live Gamer / BU110
            "2935:0001", "1edb:bd3b", "1e4e:7102");                 // Magewell, Blackmagic Web Presenter, generic UVC grabber

    private final boolean sourceIsSystemCamera;
    private final Supplier<List<String>> deviceNames;
    private List<String> matches;

    /** Uses the platform enumerator. */
    public InjectionSignal(boolean sourceIsSystemCamera) {
        this(sourceIsSystemCamera, InjectionSignal::platformDeviceNames);
    }

    public InjectionSignal(boolean sourceIsSystemCamera, Supplier<List<String>> deviceNames) {
        this.sourceIsSystemCamera = sourceIsSystemCamera;
        this.deviceNames = Objects.requireNonNull(deviceNames);
    }

    @Override public String id() { return Signals.INJECTION; }

    @Override public SignalScore score(FaceContext ctx) { return evaluate(); }

    @Override public Optional<SignalScore> scoreWithoutFace(long timestampNanos) { return Optional.of(evaluate()); }

    private SignalScore evaluate() {
        List<String> found = virtualDevices();
        if (found.isEmpty()) return SignalScore.of(id(), 0.0, 0.3);
        if (sourceIsSystemCamera) return SignalScore.veto(id(), Signals.ATTACK_INJECTION);
        return SignalScore.of(id(), 0.5, 0.0);
    }

    /** Matching virtual devices, enumerated once per analyzer (device changes mid-session are not tracked). */
    public List<String> virtualDevices() {
        if (matches == null) {
            List<String> m = new ArrayList<>();
            for (String name : deviceNames.get()) if (isVirtual(name)) m.add(name);
            matches = Collections.unmodifiableList(m);
            if (!m.isEmpty()) LOG.warn("virtual camera device(s) present: {}", m);
        }
        return matches;
    }

    public static boolean isVirtual(String deviceName) {
        if (deviceName == null) return false;
        String n = deviceName.toLowerCase(Locale.ROOT);
        for (String k : KNOWN_VIRTUAL_CAMERAS) if (n.contains(k)) return true;
        return false;
    }

    /** Video device names for the current OS; empty when unsupported. */
    public static List<String> platformDeviceNames() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) return linuxDeviceNames(Path.of("/sys/class/video4linux"));
        if (os.contains("windows")) return windowsDeviceNames();
        return List.of();
    }

    /** Reads {@code <root>/video* /name}; root is a parameter so tests can point at a fake sysfs. */
    public static List<String> linuxDeviceNames(Path root) {
        if (!Files.isDirectory(root)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            for (Path dev : (Iterable<Path>) s.sorted()::iterator) {
                Path name = dev.resolve("name");
                if (Files.isRegularFile(name)) {
                    try {
                        out.add(Files.readString(name, StandardCharsets.UTF_8).trim() + busMarker(dev));
                    } catch (IOException e) {
                        LOG.debug("cannot read {}: {}", name, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            LOG.debug("cannot list {}: {}", root, e.getMessage());
        }
        return out;
    }

    /** Marker for where a video4linux device sits in sysfs ("" for an ordinary USB or PCI camera). */
    static String busMarker(Path videoDir) {
        Path device = videoDir.resolve("device");
        if (!Files.exists(device)) return " [no physical bus]";
        try {
            Path real = device.toRealPath();
            Path driver = device.resolve("driver");
            if (Files.exists(driver) && driver.toRealPath().getFileName().toString().toLowerCase(Locale.ROOT).contains("v4l2loopback")) {
                return " [no physical bus]";
            }
            String path = real.toString().replace('\\', '/');
            if (!path.contains("/usb") && !path.contains("/pci")) return " [no physical bus]";
            for (Path p = real; p != null && p.getNameCount() > 0; p = p.getParent()) {
                Path vid = p.resolve("idVendor"), pid = p.resolve("idProduct");
                if (Files.isRegularFile(vid) && Files.isRegularFile(pid)) {
                    String id = (Files.readString(vid, StandardCharsets.UTF_8).trim() + ":" + Files.readString(pid, StandardCharsets.UTF_8).trim())
                            .toLowerCase(Locale.ROOT);
                    return KNOWN_CAPTURE_CARDS.contains(id) ? " [usb capture card " + id + "]" : "";
                }
            }
            return "";
        } catch (IOException e) {
            LOG.debug("cannot resolve {}: {}", device, e.getMessage());
            return "";
        }
    }

    /**
     * Stub: DirectShow / Media Foundation enumeration requires native COM calls. The host application should supply
     * names via {@link #InjectionSignal(boolean, Supplier)} (e.g. from {@code ICreateDevEnum} with
     * {@code CLSID_VideoInputDeviceCategory} or from the webcam library it already uses).
     */
    public static List<String> windowsDeviceNames() { return List.of(); }

    @Override public void reset() { /* device list is per-analyzer and intentionally kept */ }
}
