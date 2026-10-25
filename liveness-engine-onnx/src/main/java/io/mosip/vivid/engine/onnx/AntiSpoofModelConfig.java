package io.mosip.vivid.engine.onnx;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preprocessing recipe for one CNN anti-spoofing model. Two presets are known:
 * <ul>
 *   <li>{@code MINIFASNET} - Silent-Face-Anti-Spoofing exports ({@code 2.7_80x80_MiniFASNetV2.onnx},
 *       {@code 4_0_0_80x80_MiniFASNetV1SE.onnx}): 80x80, BGR, /255, 3 classes with "real" at index 1, crop scale
 *       parsed from the file-name prefix;</li>
 *   <li>{@code MINIFASNET_V2SE_128} - the bundled facenox/face-antispoof-onnx export: 128x128, RGB, /255,
 *       2 logits {@code [real, spoof]}, crop scale 1.5.</li>
 * </ul>
 * Anything else can be described explicitly through the properties file.
 */
public final class AntiSpoofModelConfig {
    private static final Pattern SCALE_PREFIX = Pattern.compile("^(\\d+(?:[._]\\d+)?)_.*");

    private final Path path;
    private final int inputSize;
    private final double cropScale;
    private final boolean bgr;
    private final int realClassIndex;

    public AntiSpoofModelConfig(Path path, int inputSize, double cropScale, boolean bgr, int realClassIndex) {
        this.path = Objects.requireNonNull(path); this.inputSize = inputSize; this.cropScale = cropScale;
        this.bgr = bgr; this.realClassIndex = realClassIndex;
        if (inputSize <= 0 || cropScale <= 0 || realClassIndex < 0) throw new IllegalArgumentException("invalid anti-spoof model config");
    }

    public Path path() { return path; }
    public int inputSize() { return inputSize; }
    public double cropScale() { return cropScale; }
    public boolean bgr() { return bgr; }
    public int realClassIndex() { return realClassIndex; }

    /** Guesses the recipe from the file name; {@code inputSizeHint} (e.g. from a bundle manifest) wins when &gt; 0. */
    public static AntiSpoofModelConfig preset(Path model, int inputSizeHint) {
        String name = model.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.contains("minifasnet") && !name.contains("128")) {
            double scale = 2.7;
            Matcher m = SCALE_PREFIX.matcher(name);
            if (m.matches()) {
                String s = m.group(1).replace('_', '.');
                try { scale = Double.parseDouble(s); } catch (NumberFormatException ignored) { /* keep default */ }
            }
            return new AntiSpoofModelConfig(model, inputSizeHint > 0 ? inputSizeHint : 80, scale, true, 1);
        }
        return new AntiSpoofModelConfig(model, inputSizeHint > 0 ? inputSizeHint : 128, 1.5, false, 0);
    }

    @Override public String toString() {
        return path.getFileName() + "{" + inputSize + "px x" + cropScale + (bgr ? " BGR" : " RGB") + " real@" + realClassIndex + "}";
    }
}
