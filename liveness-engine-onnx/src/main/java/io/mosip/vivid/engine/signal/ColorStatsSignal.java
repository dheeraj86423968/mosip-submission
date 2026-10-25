package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.engine.image.ImageOps;
import io.mosip.vivid.engine.image.RgbImage;

/**
 * S3 - specular and colour statistics on an inner face crop (0.8x box, 64 px).
 *
 * <p>Features: mean and spread of HSV saturation, mean value, specular-highlight ratio (V &gt; 0.93 and S &lt; 0.2),
 * R-G and G-B channel correlation, chromaticity spread (std of r = R/(R+G+B) plus std of g), blue shift
 * ((mean B - mean R) / 255) and clipping (fraction of channel samples at 0 or 255).
 * Prints have flat reflectance: low saturation, narrow chromaticity, few true speculars. Screens show gamut clipping
 * and a blue cast. Raw logit
 * {@code 5 (0.25 - sMean) + 20 (0.02 - chromaSpread) + 6 blueShift + 8 (specular - 0.05) + 5 clipping - 0.4},
 * then Platt calibration. Attack class is {@code SCREEN_REPLAY} when blue shift or clipping dominate, else {@code PRINT}.
 * Weights are untrained defaults chosen so that natural skin scores well below 0.5; calibrate from the corpus.
 */
public final class ColorStatsSignal implements Signal {
    public static final int SIZE = 64;

    /** Feature vector of one crop. */
    public static final class Features {
        public double sMean, sStd, vMean, specular, corrRG, corrGB, chromaSpread, blueShift, clipping;
        @Override public String toString() {
            return String.format("s=%.2f/%.2f v=%.2f spec=%.3f corrRG=%.2f corrGB=%.2f chroma=%.3f blue=%.2f clip=%.3f",
                    sMean, sStd, vMean, specular, corrRG, corrGB, chromaSpread, blueShift, clipping);
        }
    }

    private final PlattCalibration calibration;
    private Features last;

    public ColorStatsSignal(PlattCalibration calibration) {
        this.calibration = calibration == null ? PlattCalibration.identity() : calibration;
    }

    @Override public String id() { return Signals.COLOR_STATS; }

    @Override
    public SignalScore score(FaceContext ctx) {
        Features f = features(ctx.faceCrop(0.8, SIZE));
        last = f;
        double p = calibration.apply(rawLogit(f));
        double confidence = ImageOps.clamp01((ctx.interOcular() - 16) / 24.0) * (f.vMean > 0.12 ? 1.0 : 0.3);
        String cls = p > 0.5 ? ((f.blueShift > 0.05 || f.clipping > 0.1) ? Signals.ATTACK_SCREEN : Signals.ATTACK_PRINT) : null;
        return new SignalScore(id(), p, confidence, false, cls);
    }

    public static double rawLogit(Features f) {
        return 5 * (0.25 - f.sMean) + 20 * (0.02 - f.chromaSpread) + 6 * f.blueShift + 8 * (f.specular - 0.05) + 5 * f.clipping - 0.4;
    }

    public static Features features(RgbImage img) {
        int n = img.width() * img.height();
        byte[] d = img.data();
        float[] hsv = new float[3];
        double sSum = 0, s2Sum = 0, vSum = 0, rSum = 0, gSum = 0, bSum = 0;
        double rr = 0, gg = 0, bb = 0, rg = 0, gb = 0;
        double crSum = 0, cr2 = 0, cgSum = 0, cg2 = 0;
        int spec = 0, clip = 0;
        for (int i = 0, j = 0; i < n; i++, j += 3) {
            int r = d[j] & 0xFF, g = d[j + 1] & 0xFF, b = d[j + 2] & 0xFF;
            ImageOps.rgbToHsv(r, g, b, hsv);
            sSum += hsv[1]; s2Sum += hsv[1] * hsv[1]; vSum += hsv[2];
            if (hsv[2] > 0.93f && hsv[1] < 0.2f) spec++;
            if (r == 0 || r == 255) clip++;
            if (g == 0 || g == 255) clip++;
            if (b == 0 || b == 255) clip++;
            rSum += r; gSum += g; bSum += b;
            rr += r * r; gg += g * g; bb += b * b; rg += r * g; gb += g * b;
            double sum = r + g + b + 1.0;
            double cr = r / sum, cg = g / sum;
            crSum += cr; cr2 += cr * cr; cgSum += cg; cg2 += cg * cg;
        }
        Features f = new Features();
        f.sMean = sSum / n;
        f.sStd = Math.sqrt(Math.max(0, s2Sum / n - f.sMean * f.sMean));
        f.vMean = vSum / n;
        f.specular = (double) spec / n;
        f.clipping = clip / (3.0 * n);
        double mr = rSum / n, mg = gSum / n, mb = bSum / n;
        double vr = rr / n - mr * mr, vg = gg / n - mg * mg, vb = bb / n - mb * mb;
        f.corrRG = corr(rg / n - mr * mg, vr, vg);
        f.corrGB = corr(gb / n - mg * mb, vg, vb);
        double mcr = crSum / n, mcg = cgSum / n;
        f.chromaSpread = Math.sqrt(Math.max(0, cr2 / n - mcr * mcr)) + Math.sqrt(Math.max(0, cg2 / n - mcg * mcg));
        f.blueShift = (mb - mr) / 255.0;
        return f;
    }

    private static double corr(double cov, double va, double vb) {
        double den = Math.sqrt(Math.max(va, 1e-9) * Math.max(vb, 1e-9));
        return Math.max(-1, Math.min(1, cov / den));
    }

    public Features lastFeatures() { return last; }

    @Override public void reset() { last = null; }
}
