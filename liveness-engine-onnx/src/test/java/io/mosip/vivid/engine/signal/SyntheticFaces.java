package io.mosip.vivid.engine.signal;

import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.image.RgbImage;

import java.util.Random;
import java.util.function.IntBinaryOperator;

/** Synthetic frames for signal tests: a face box in the middle of a 320x240 frame filled by a pixel function. */
final class SyntheticFaces {
    static final int W = 320, H = 240;

    private SyntheticFaces() {}

    /** Detection of a 100 px face centred in the frame with plausible landmarks (IOD 40 px). */
    static FaceDetection face() {
        return new FaceDetection(110, 70, 100, 110, 0.95,
                new double[] {140, 110, 180, 110, 160, 135, 145, 155, 175, 155});
    }

    /** Frame whose RGB at (x, y) comes from {@code rgb.apply(x, y)} packed as 0xRRGGBB. */
    static RgbImage frame(IntBinaryOperator rgb) {
        byte[] d = new byte[W * H * 3];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = rgb.applyAsInt(x, y), i = (y * W + x) * 3;
            d[i] = (byte) (p >> 16); d[i + 1] = (byte) (p >> 8); d[i + 2] = (byte) p;
        }
        return new RgbImage(W, H, d);
    }

    /** Skin-toned frame with mild random texture (stand-in for a live face). */
    static RgbImage skinTexture(long seed) {
        Random r = new Random(seed);
        return frame((x, y) -> {
            int n = (int) (r.nextGaussian() * 6);
            int sh = (int) (8 * Math.sin(x / 9.0) * Math.cos(y / 11.0));
            return rgb(205 + n + sh, 150 + n + sh, 120 + n + sh);
        });
    }

    static RgbImage flat(int r, int g, int b) { return frame((x, y) -> rgb(r, g, b)); }

    static int rgb(int r, int g, int b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    static FaceContext ctx(RgbImage img) {
        return new FaceContext(img, face(), 0, 0, null);
    }
}
