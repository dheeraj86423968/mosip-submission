package io.mosip.vivid.engine.image;

import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Converts any {@link FaceFrame} pixel format into {@link RgbImage}. YUV conversions use BT.601 limited range
 * (the Android camera convention). JPEG decoding goes through {@link ImageIO}.
 */
public final class FrameDecoder {
    private FrameDecoder() {}

    public static RgbImage toRgb(FaceFrame frame) {
        ByteBuffer buf = frame.pixels();
        int w = frame.width(), h = frame.height();
        PixelFormat f = frame.format();
        switch (f) {
            case MJPEG: return decodeJpeg(buf, w, h);
            case RGB888: return new RgbImage(w, h, take(buf, w * h * 3, f));
            case BGR888: {
                byte[] d = take(buf, w * h * 3, f);
                for (int i = 0; i < d.length; i += 3) { byte t = d[i]; d[i] = d[i + 2]; d[i + 2] = t; }
                return new RgbImage(w, h, d);
            }
            case GRAY8: {
                byte[] g = take(buf, w * h, f);
                byte[] d = new byte[w * h * 3];
                for (int i = 0, j = 0; i < g.length; i++, j += 3) { d[j] = g[i]; d[j + 1] = g[i]; d[j + 2] = g[i]; }
                return new RgbImage(w, h, d);
            }
            case GRAY16: {
                byte[] g = take(buf, w * h * 2, f);
                boolean little = buf.order() == java.nio.ByteOrder.LITTLE_ENDIAN;
                byte[] d = new byte[w * h * 3];
                for (int i = 0, j = 0; i < g.length; i += 2, j += 3) {
                    byte hi = little ? g[i + 1] : g[i];
                    d[j] = hi; d[j + 1] = hi; d[j + 2] = hi;
                }
                return new RgbImage(w, h, d);
            }
            case NV21: return nv21ToRgb(take(buf, w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2), f), w, h);
            case YUV420: return i420ToRgb(take(buf, w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2), f), w, h);
            default: throw new InvalidFrameException("unsupported pixel format " + f);
        }
    }

    /** Decodes a JPEG; the declared frame size must match the decoded size. */
    public static RgbImage decodeJpeg(ByteBuffer jpeg, int expectedWidth, int expectedHeight) {
        byte[] bytes = new byte[jpeg.remaining()];
        jpeg.get(bytes);
        return decodeJpeg(bytes, expectedWidth, expectedHeight);
    }

    public static RgbImage decodeJpeg(byte[] jpeg, int expectedWidth, int expectedHeight) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException e) {
            throw new InvalidFrameException("JPEG decode failed: " + e.getMessage(), e);
        }
        if (img == null) throw new InvalidFrameException("not a decodable image (" + jpeg.length + " bytes)");
        if (expectedWidth > 0 && (img.getWidth() != expectedWidth || img.getHeight() != expectedHeight)) {
            throw new InvalidFrameException("JPEG is " + img.getWidth() + "x" + img.getHeight() + " but frame declares " + expectedWidth + "x" + expectedHeight);
        }
        return RgbImage.fromBufferedImage(img);
    }

    private static byte[] take(ByteBuffer buf, int needed, PixelFormat f) {
        if (buf.remaining() < needed) {
            throw new InvalidFrameException(f + " frame needs " + needed + " bytes but buffer has " + buf.remaining());
        }
        byte[] d = new byte[needed];
        buf.get(d);
        return d;
    }

    static RgbImage nv21ToRgb(byte[] yuv, int w, int h) {
        byte[] out = new byte[w * h * 3];
        int cw = (w + 1) / 2;
        int uvStart = w * h;
        for (int y = 0; y < h; y++) {
            int uvRow = uvStart + (y / 2) * cw * 2;
            for (int x = 0; x < w; x++) {
                int yy = yuv[y * w + x] & 0xFF;
                int uvIndex = uvRow + (x / 2) * 2;
                int v = yuv[uvIndex] & 0xFF;
                int u = yuv[uvIndex + 1] & 0xFF;
                writeYuv(out, (y * w + x) * 3, yy, u, v);
            }
        }
        return new RgbImage(w, h, out);
    }

    static RgbImage i420ToRgb(byte[] yuv, int w, int h) {
        byte[] out = new byte[w * h * 3];
        int cw = (w + 1) / 2, ch = (h + 1) / 2;
        int uStart = w * h, vStart = uStart + cw * ch;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int yy = yuv[y * w + x] & 0xFF;
                int ci = (y / 2) * cw + (x / 2);
                int u = yuv[uStart + ci] & 0xFF;
                int v = yuv[vStart + ci] & 0xFF;
                writeYuv(out, (y * w + x) * 3, yy, u, v);
            }
        }
        return new RgbImage(w, h, out);
    }

    /** BT.601 limited-range YUV to RGB with fixed-point arithmetic (x1024). */
    private static void writeYuv(byte[] out, int i, int y, int u, int v) {
        int c = 1192 * (y - 16);
        int d = u - 128, e = v - 128;
        int r = (c + 1634 * e + 512) >> 10;
        int g = (c - 400 * d - 832 * e + 512) >> 10;
        int b = (c + 2066 * d + 512) >> 10;
        out[i] = (byte) clamp(r); out[i + 1] = (byte) clamp(g); out[i + 2] = (byte) clamp(b);
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
