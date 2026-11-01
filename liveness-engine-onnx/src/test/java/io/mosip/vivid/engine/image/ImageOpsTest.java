package io.mosip.vivid.engine.image;

import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class ImageOpsTest {
    static RgbImage solid(int w, int h, int r, int g, int b) {
        byte[] d = new byte[w * h * 3];
        for (int i = 0; i < d.length; i += 3) { d[i] = (byte) r; d[i + 1] = (byte) g; d[i + 2] = (byte) b; }
        return new RgbImage(w, h, d);
    }

    static RgbImage gradient(int w, int h) {
        byte[] d = new byte[w * h * 3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 3;
            d[i] = (byte) x; d[i + 1] = (byte) y; d[i + 2] = (byte) (x + y);
        }
        return new RgbImage(w, h, d);
    }

    @Test
    void grayUsesRec601Weights() {
        FloatImage g = ImageOps.toGray(solid(2, 2, 255, 0, 0));
        assertThat(g.get(1, 1)).isCloseTo(76.245f, offset(0.01f));
    }

    @Test
    void resizeKeepsSolidColourAndInterpolatesGradients() {
        RgbImage r = ImageOps.resize(solid(10, 7, 10, 20, 30), 33, 4);
        assertThat(r.width()).isEqualTo(33);
        assertThat(r.r(17, 2)).isEqualTo(10);
        assertThat(r.b(0, 3)).isEqualTo(30);
        RgbImage g = gradient(100, 100);
        RgbImage half = ImageOps.resize(g, 50, 50);
        // pixel 10 of the half-size image samples between source columns 20 and 21
        assertThat(half.r(10, 0)).isBetween(20, 21);
        assertThat(ImageOps.resize(g, 100, 100)).isSameAs(g);
    }

    @Test
    void cropPaddingModes() {
        RgbImage g = gradient(5, 5);
        RgbImage z = ImageOps.crop(g, -2, 0, 4, 1, ImageOps.Padding.ZERO);
        assertThat(z.r(0, 0)).isZero();
        assertThat(z.r(2, 0)).isEqualTo(0);
        assertThat(z.r(3, 0)).isEqualTo(1);
        RgbImage c = ImageOps.crop(g, -2, 0, 3, 1, ImageOps.Padding.CLAMP);
        assertThat(c.r(0, 0)).isEqualTo(0);
        RgbImage refl = ImageOps.crop(g, -2, 0, 5, 1, ImageOps.Padding.REFLECT);
        // reflect_101: x=-2 -> 2, x=-1 -> 1
        assertThat(refl.r(0, 0)).isEqualTo(2);
        assertThat(refl.r(1, 0)).isEqualTo(1);
        RgbImage far = ImageOps.crop(g, 4, 0, 3, 1, ImageOps.Padding.REFLECT);
        assertThat(far.r(1, 0)).isEqualTo(3);
        RgbImage face = ImageOps.cropFace(g, 1, 1, 2, 2, 1.5, ImageOps.Padding.REFLECT);
        assertThat(face.width()).isEqualTo(3);
    }

    @Test
    void chwTensorLayoutAndNormalisation() {
        RgbImage img = solid(2, 1, 10, 20, 30);
        float[] rgb = ImageOps.toChw(img, false, 1f, null, null);
        assertThat(rgb).containsExactly(10, 10, 20, 20, 30, 30);
        float[] bgr = ImageOps.toChw(img, true, 1f / 255f, new float[] {0.5f, 0.5f, 0.5f}, new float[] {0.5f, 0.5f, 0.5f});
        assertThat(bgr[0]).isCloseTo((30 / 255f - 0.5f) / 0.5f, offset(1e-6f));
        assertThat(bgr[4]).isCloseTo((10 / 255f - 0.5f) / 0.5f, offset(1e-6f));
    }

    @Test
    void laplacianVarianceSeparatesFlatFromTexture() {
        assertThat(ImageOps.laplacianVariance(new FloatImage(16, 16))).isZero();
        float[] noise = new float[32 * 32];
        Random r = new Random(1);
        for (int i = 0; i < noise.length; i++) noise[i] = r.nextFloat() * 255;
        assertThat(ImageOps.laplacianVariance(new FloatImage(32, 32, noise))).isGreaterThan(1000);
        // a linear ramp has zero Laplacian
        float[] ramp = new float[16 * 16];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) ramp[y * 16 + x] = 3 * x + 2 * y;
        assertThat(ImageOps.laplacianVariance(new FloatImage(16, 16, ramp))).isCloseTo(0, offset(1e-6));
    }

    @Test
    void meanStdAndClipping() {
        double[] ms = ImageOps.meanStd(new float[] {2, 4, 4, 4, 5, 5, 7, 9});
        assertThat(ms[0]).isCloseTo(5, offset(1e-9));
        assertThat(ms[1]).isCloseTo(2, offset(1e-9));
        assertThat(ImageOps.clippedFraction(new FloatImage(2, 2, new float[] {0, 100, 255, 128}), 5, 250)).isEqualTo(0.5);
        double[] m = ImageOps.meanRgb(solid(4, 4, 1, 2, 3), -2, -2, 4, 4);
        assertThat(m).containsExactly(1, 2, 3, 4);
    }

    @Test
    void flipHorizontalMirrorsRowsAndIsAnInvolution() {
        byte[] d = new byte[2 * 3 * 3];
        for (int i = 0; i < d.length; i++) d[i] = (byte) i;
        RgbImage img = new RgbImage(3, 2, d);
        RgbImage f = ImageOps.flipHorizontal(img);
        // first row: pixel 0 (0,1,2) moves to x = 2
        assertThat(f.data()[6]).isEqualTo((byte) 0);
        assertThat(f.data()[7]).isEqualTo((byte) 1);
        assertThat(f.data()[0]).isEqualTo((byte) 6);
        // second row keeps its row
        assertThat(f.data()[9 + 6]).isEqualTo((byte) 9);
        assertThat(ImageOps.flipHorizontal(f).data()).isEqualTo(d);
    }

    @Test
    void silentFaceCropStaysInsideTheFrame() {
        RgbImage img = new RgbImage(100, 80, new byte[100 * 80 * 3]);
        // centred box, scale fits: 20x30 box * 2 = 40x60, upstream keeps the inclusive right/bottom edge
        RgbImage a = ImageOps.cropFaceFit(img, 40, 25, 20, 30, 2.0);
        assertThat(a.width()).isEqualTo(41);
        assertThat(a.height()).isEqualTo(61);
        // scale 4 would need 120 px of height: reduced to (80-1)/30, then shifted inside
        RgbImage b = ImageOps.cropFaceFit(img, 0, 0, 20, 30, 4.0);
        assertThat(b.height()).isLessThanOrEqualTo(80);
        assertThat(b.width()).isLessThanOrEqualTo(100);
    }

    @Test
    void hsv() {
        float[] hsv = new float[3];
        ImageOps.rgbToHsv(255, 0, 0, hsv);
        assertThat(hsv).containsExactly(0f, 1f, 1f);
        ImageOps.rgbToHsv(0, 0, 255, hsv);
        assertThat(hsv[0]).isCloseTo(240f, offset(1e-3f));
        ImageOps.rgbToHsv(128, 128, 128, hsv);
        assertThat(hsv[1]).isZero();
    }

    @Test
    void warpAffineIdentityAndTranslation() {
        RgbImage g = gradient(20, 20);
        RgbImage same = ImageOps.warpAffine(g, new double[] {1, 0, 0, 0, 1, 0}, 10, 10);
        assertThat(same.r(5, 5)).isEqualTo(g.r(5, 5));
        RgbImage shifted = ImageOps.warpAffine(g, new double[] {1, 0, -3, 0, 1, 0}, 10, 10);
        assertThat(shifted.r(2, 2)).isEqualTo(g.r(5, 2));
    }

    @Test
    void decodesEveryPixelFormat() throws Exception {
        int w = 4, h = 2;
        RgbImage g = gradient(w, h);
        assertThat(FrameDecoder.toRgb(frame(w, h, PixelFormat.RGB888, g.data())).data()).containsExactly(g.data());
        byte[] bgr = g.data().clone();
        for (int i = 0; i < bgr.length; i += 3) { byte t = bgr[i]; bgr[i] = bgr[i + 2]; bgr[i + 2] = t; }
        assertThat(FrameDecoder.toRgb(frame(w, h, PixelFormat.BGR888, bgr)).data()).containsExactly(g.data());
        RgbImage gray = FrameDecoder.toRgb(frame(w, h, PixelFormat.GRAY8, new byte[] {0, 50, 100, (byte) 200, 1, 2, 3, 4}));
        assertThat(gray.r(3, 0)).isEqualTo(200);
        assertThat(gray.b(3, 0)).isEqualTo(200);

        // YUV mid-grey: Y=128, U=V=128 -> about 130 in every channel (limited range expansion)
        byte[] yuv = new byte[w * h + 2 * (w / 2) * (h / 2)];
        java.util.Arrays.fill(yuv, (byte) 128);
        for (PixelFormat f : new PixelFormat[] {PixelFormat.NV21, PixelFormat.YUV420}) {
            RgbImage rgb = FrameDecoder.toRgb(frame(w, h, f, yuv));
            assertThat(rgb.r(0, 0)).isBetween(129, 131);
            assertThat(rgb.g(3, 1)).isBetween(129, 131);
        }
        // pure red-ish chroma: V high -> R > B
        byte[] nv21 = yuv.clone();
        nv21[w * h] = (byte) 240; // V
        nv21[w * h + 1] = (byte) 128; // U
        RgbImage red = FrameDecoder.toRgb(frame(w, h, PixelFormat.NV21, nv21));
        assertThat(red.r(0, 0)).isGreaterThan(red.b(0, 0) + 100);

        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(solid(16, 8, 200, 100, 50).toBufferedImage(), "jpg", jpg);
        RgbImage decoded = FrameDecoder.toRgb(frame(16, 8, PixelFormat.MJPEG, jpg.toByteArray()));
        assertThat(decoded.r(8, 4)).isBetween(190, 210);
        assertThat(decoded.b(8, 4)).isBetween(40, 60);
    }

    @Test
    void invalidFramesAreRejected() {
        assertThatThrownBy(() -> FrameDecoder.toRgb(frame(4, 4, PixelFormat.RGB888, new byte[10])))
                .isInstanceOf(InvalidFrameException.class).hasMessageContaining("needs 48 bytes");
        assertThatThrownBy(() -> FrameDecoder.toRgb(frame(4, 4, PixelFormat.MJPEG, new byte[] {1, 2, 3})))
                .isInstanceOf(InvalidFrameException.class);
    }

    static FaceFrame frame(int w, int h, PixelFormat f, byte[] data) {
        return FaceFrame.builder().width(w).height(h).format(f).pixels(data).build();
    }
}
