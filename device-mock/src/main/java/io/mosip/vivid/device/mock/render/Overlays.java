package io.mosip.vivid.device.mock.render;

import io.mosip.vivid.device.mock.scenario.OverlayKind;

import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Pixel-level overlays that imitate presentation attack instrument texture. They are applied in place to
 * TYPE_INT_RGB images and are deterministic for a given seed and time.
 */
public final class Overlays {
    private Overlays() {}

    /** Apply {@code kind} to {@code img}. {@code tMs} animates time-dependent overlays (moiré drift). */
    public static void apply(OverlayKind kind, BufferedImage img, long seed, long tMs) {
        switch (kind) {
            case NONE -> { }
            case PRINT_TEXTURE -> printTexture(img, seed);
            case MOIRE_GRID -> moireGrid(img, tMs);
        }
    }

    /**
     * Paper print: per-pixel grain, mild desaturation and brightness lift (ink gamut), plus a halftone dot
     * lattice. The grain is seeded once so consecutive frames of a "photo" carry the identical texture, which
     * is exactly what a static-texture detector keys on.
     */
    static void printTexture(BufferedImage img, long seed) {
        Random rnd = new Random(seed);
        int w = img.getWidth(), h = img.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                // desaturate 35%, lift blacks (paper cannot be fully black)
                r = (int) (r * 0.65 + lum * 0.35) * 220 / 255 + 20;
                g = (int) (g * 0.65 + lum * 0.35) * 220 / 255 + 20;
                b = (int) (b * 0.65 + lum * 0.35) * 220 / 255 + 20;
                int grain = rnd.nextInt(25) - 12;
                int dot = ((x % 4 == 0) && (y % 4 == 0)) ? -18 : 0;   // halftone lattice
                r = clamp(r + grain + dot); g = clamp(g + grain + dot); b = clamp(b + grain + dot);
                row[x] = (r << 16) | (g << 8) | b;
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
    }

    /**
     * Screen replay: a fine pixel grid (dark gaps between emitter cells) plus a slow drift and a blue-ish cast,
     * which together produce moiré-like beating once the frame is resampled by a camera.
     */
    static void moireGrid(BufferedImage img, long tMs) {
        int w = img.getWidth(), h = img.getHeight();
        int shift = (int) ((tMs / 40) % 3);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            boolean gapRow = ((y + shift) % 3) == 0;
            for (int x = 0; x < w; x++) {
                int p = row[x];
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                boolean gapCol = ((x + shift) % 3) == 0;
                if (gapRow || gapCol) { r = r * 55 / 100; g = g * 55 / 100; b = b * 55 / 100; }
                else { r = clamp(r + 8); g = clamp(g + 10); b = clamp(b + 28); }
                // slow luminance beat (refresh-rate interference)
                int beat = (int) (6 * Math.sin((x * 0.19) + (y * 0.07) + tMs * 0.004));
                r = clamp(r + beat); g = clamp(g + beat); b = clamp(b + beat);
                row[x] = (r << 16) | (g << 8) | b;
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
