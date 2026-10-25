package io.mosip.vivid.device.mock;

import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/** Converts rendered {@link BufferedImage}s into the byte layouts named by {@link PixelFormat}. Stateless. */
public final class FrameEncoder {
    private FrameEncoder() {}

    /** Packed RGB888, row-major, no padding (3 bytes per pixel). */
    public static byte[] toRgb888(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] out = new byte[w * h * 3];
        int[] row = new int[w];
        int o = 0;
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                out[o++] = (byte) (p >> 16); out[o++] = (byte) (p >> 8); out[o++] = (byte) p;
            }
        }
        return out;
    }

    /** Baseline JPEG at the given quality in (0,1]. */
    public static byte[] toJpeg(BufferedImage img, float quality) {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        if (!it.hasNext()) throw new IllegalStateException("no JPEG writer available in this JRE");
        ImageWriter writer = it.next();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
             MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.05f, Math.min(1f, quality)));
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
            ios.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
    }

    /** Encode in the requested format. Only RGB888 and MJPEG are produced by the mock. */
    public static byte[] encode(BufferedImage img, PixelFormat format, float jpegQuality) {
        return switch (format) {
            case RGB888 -> toRgb888(img);
            case MJPEG -> toJpeg(img, jpegQuality);
            default -> throw new IllegalArgumentException("mock cannot produce " + format + "; use RGB888 or MJPEG");
        };
    }

    /** Build a {@link FaceFrame} with optional depth (GRAY16 little-endian mm) and IR (GRAY8) planes; null planes are omitted. */
    public static FaceFrame frame(long sequence, long timestampNanos, int width, int height, PixelFormat format, byte[] pixels,
                                  java.nio.ByteBuffer depth, java.nio.ByteBuffer ir) {
        return FaceFrame.builder().sequence(sequence).timestampNanos(timestampNanos)
                .width(width).height(height).format(format).pixels(pixels).depth(depth).ir(ir).build();
    }

    /** Build a {@link FaceFrame} around encoded bytes. */
    public static FaceFrame frame(long sequence, long timestampNanos, int width, int height, PixelFormat format, byte[] pixels) {
        return FaceFrame.builder().sequence(sequence).timestampNanos(timestampNanos)
                .width(width).height(height).format(format).pixels(pixels).build();
    }
}
