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
 *       {@code 4_0_0_80x80_MiniFASNetV1SE.onnx}): 80x80, BGR, raw 0..255 pixel values (the upstream
 *       {@code to_tensor} does not divide by 255), 3 classes with "real" at index 1, crop scale parsed from the
 *       file-name prefix;</li>
 *   <li>{@code MINIFASNET_V2SE_128} - the bundled facenox/face-antispoof-onnx export: 128x128, RGB, /255,
 *       2 logits {@code [real, spoof]}, square crop at {@value #BUNDLED_CROP_SCALE}x the detector box. 1.2 replaced
 *       1.5 in November 2026: on the proxy evaluation it separated prints and screens better on the training people
 *       and was confirmed on held-out people (docs/testing/proxy-eval/improvement-2026-11.md).</li>
 * </ul>
 * Anything else can be described explicitly through the properties file. {@code flip} adds a horizontally mirrored
 * view of the same crop and averages the two probabilities (test-time augmentation; off by default). {@code fitCrop}
 * selects the upstream Silent-Face crop ({@code CropImage._get_new_box}): the detector box scaled by
 * {@code cropScale} in each dimension, the scale reduced so the crop fits in the frame, shifted inside the frame and
 * resized without keeping the aspect ratio. Otherwise the crop is a square of the box's longer side times
 * {@code cropScale}, reflect-padded at the frame edge.
 */
public final class AntiSpoofModelConfig {
    /** Square-crop scale of the bundled 128 px model (box longer side multiplier). */
    public static final double BUNDLED_CROP_SCALE = 1.2;
    private static final Pattern SCALE_PREFIX = Pattern.compile("^(\\d+(?:[._]\\d+)?)_.*");

    private final Path path;
    private final int inputSize;
    private final double cropScale;
    private final boolean bgr;
    private final int realClassIndex;
    private final float inputScale;
    private final boolean flip;
    private final boolean fitCrop;

    /** Recipe with pixel values divided by 255, a square reflect-padded crop and no flipped view. */
    public AntiSpoofModelConfig(Path path, int inputSize, double cropScale, boolean bgr, int realClassIndex) {
        this(path, inputSize, cropScale, bgr, realClassIndex, 1f / 255f, false, false);
    }

    /**
     * @param inputScale multiplier applied to 0..255 pixel values ({@code 1/255} for most exports, {@code 1} for the
     *                   original Silent-Face models)
     * @param flip       also score the horizontally mirrored crop and average
     * @param fitCrop    Silent-Face crop (scaled box kept inside the frame, aspect not preserved) instead of a square
     */
    public AntiSpoofModelConfig(Path path, int inputSize, double cropScale, boolean bgr, int realClassIndex, float inputScale,
                                boolean flip, boolean fitCrop) {
        this.path = Objects.requireNonNull(path); this.inputSize = inputSize; this.cropScale = cropScale;
        this.bgr = bgr; this.realClassIndex = realClassIndex; this.inputScale = inputScale; this.flip = flip; this.fitCrop = fitCrop;
        if (inputSize <= 0 || cropScale <= 0 || realClassIndex < 0 || !(inputScale > 0)) throw new IllegalArgumentException("invalid anti-spoof model config");
    }

    public Path path() { return path; }
    public int inputSize() { return inputSize; }
    public double cropScale() { return cropScale; }
    public boolean bgr() { return bgr; }
    public int realClassIndex() { return realClassIndex; }
    public float inputScale() { return inputScale; }
    public boolean flip() { return flip; }
    public boolean fitCrop() { return fitCrop; }

    /** Guesses the recipe from the file name; {@code inputSizeHint} (e.g. from a bundle manifest) wins when &gt; 0. */
    public static AntiSpoofModelConfig preset(Path model, int inputSizeHint) {
        if (isSilentFace(model)) {
            String name = model.getFileName().toString().toLowerCase(Locale.ROOT);
            double scale = 2.7;
            Matcher m = SCALE_PREFIX.matcher(name);
            if (m.matches()) {
                String s = m.group(1).replace('_', '.');
                try { scale = Double.parseDouble(s); } catch (NumberFormatException ignored) { /* keep default */ }
            }
            return new AntiSpoofModelConfig(model, inputSizeHint > 0 ? inputSizeHint : 80, scale, true, 1, 1f, false, true);
        }
        return new AntiSpoofModelConfig(model, inputSizeHint > 0 ? inputSizeHint : 128, BUNDLED_CROP_SCALE, false, 0);
    }

    /** True for an original Silent-Face-Anti-Spoofing export ({@code *minifasnet*} without {@code 128} in the name). */
    public static boolean isSilentFace(Path model) {
        String name = model.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains("minifasnet") && !name.contains("128");
    }

    @Override public String toString() {
        return path.getFileName() + "{" + inputSize + "px x" + cropScale + (bgr ? " BGR" : " RGB") + " real@" + realClassIndex
                + (inputScale == 1f ? " raw" : "") + (fitCrop ? " fit" : "") + (flip ? " +flip" : "") + "}";
    }
}
