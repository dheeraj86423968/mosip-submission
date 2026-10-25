package io.mosip.vivid.engine.pose;

import io.mosip.vivid.engine.image.FloatImage;

/**
 * Finds the dark horizontal band in a small grayscale patch: the visible iris/pupil of an eye or the oral cavity of
 * an open mouth. A row profile is built from the central columns, the darkest row is located and the band is grown
 * up and down while rows stay below {@code min + BAND_FRACTION * (max - min)}. The band height in pixels, divided by
 * the patch width, approximates the eye / mouth aspect ratio normally measured from dense landmarks.
 */
final class DarkBand {
    static final double BAND_FRACTION = 0.35;

    final int top, bottom;        // inclusive row range of the band
    final double centroidX;       // column centroid of darkness inside the band, in patch pixels
    final double contrast;        // max - min of the row profile (0..255)

    private DarkBand(int top, int bottom, double centroidX, double contrast) {
        this.top = top; this.bottom = bottom; this.centroidX = centroidX; this.contrast = contrast;
    }

    int height() { return bottom - top + 1; }
    double centreY() { return (top + bottom) / 2.0; }

    static DarkBand find(FloatImage patch) {
        int w = patch.width(), h = patch.height();
        int c0 = (int) Math.round(w * 0.2), c1 = Math.max(c0 + 1, (int) Math.round(w * 0.8));
        double[] rows = new double[h];
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        int darkest = 0;
        for (int y = 0; y < h; y++) {
            double s = 0;
            for (int x = c0; x < c1; x++) s += patch.get(x, y);
            rows[y] = s / (c1 - c0);
            if (rows[y] < min) { min = rows[y]; darkest = y; }
            if (rows[y] > max) max = rows[y];
        }
        double thr = min + BAND_FRACTION * (max - min);
        int top = darkest, bottom = darkest;
        while (top > 0 && rows[top - 1] < thr) top--;
        while (bottom < h - 1 && rows[bottom + 1] < thr) bottom++;
        // horizontal centroid of darkness within the band
        double wsum = 0, xsum = 0;
        for (int y = top; y <= bottom; y++) {
            for (int x = 0; x < w; x++) {
                double d = thr - patch.get(x, y);
                if (d > 0) { wsum += d; xsum += d * x; }
            }
        }
        double cx = wsum > 0 ? xsum / wsum : (w - 1) / 2.0;
        return new DarkBand(top, bottom, cx, max - min);
    }
}
