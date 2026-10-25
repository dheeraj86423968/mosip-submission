package io.mosip.vivid.demo;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Minimal pure-Java Motion-JPEG AVI writer (RIFF AVI 1.0, one video stream, {@code idx1} index). Each frame is a
 * baseline JPEG produced by ImageIO. Files are limited to 1 GB, which is the AVI 1.0 limit in practice.
 *
 * <pre>
 * RIFF 'AVI '
 *   LIST 'hdrl'  avih, LIST 'strl' (strh 'vids'/'MJPG', strf BITMAPINFOHEADER)
 *   LIST 'movi'  '00dc' chunks
 *   idx1         one 16-byte entry per frame, offsets relative to the 'movi' fourcc
 * </pre>
 */
public final class AviMjpegWriter implements AutoCloseable {
    private static final long MAX_BYTES = 1L << 30;

    private final RandomAccessFile out;
    private final int width, height;
    private final double fps;
    private final float quality;
    private final ByteArrayOutputStream index = new ByteArrayOutputStream();
    private final long riffSizePos, avihFramesPos, avihBufferPos, strhLengthPos, strhBufferPos, moviSizePos, moviFourccPos;
    private int frames;
    private int maxChunk;
    private boolean closed;

    /**
     * @param quality JPEG quality in (0, 1]
     */
    public AviMjpegWriter(Path file, int width, int height, double fps, float quality) throws IOException {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("size must be positive");
        if (!(fps > 0)) throw new IllegalArgumentException("fps must be positive");
        if (!(quality > 0 && quality <= 1)) throw new IllegalArgumentException("quality must be in (0,1]");
        this.width = width; this.height = height; this.fps = fps; this.quality = quality;
        java.nio.file.Files.deleteIfExists(file);
        out = new RandomAccessFile(file.toFile(), "rw");

        fourcc("RIFF"); riffSizePos = out.getFilePointer(); u32(0); fourcc("AVI ");
        fourcc("LIST"); long hdrlSizePos = out.getFilePointer(); u32(0); long hdrlStart = out.getFilePointer(); fourcc("hdrl");
        // MainAVIHeader
        fourcc("avih"); u32(56);
        u32((int) Math.round(1_000_000.0 / fps));   // dwMicroSecPerFrame
        u32(0);                                      // dwMaxBytesPerSec
        u32(0);                                      // dwPaddingGranularity
        u32(0x10);                                   // dwFlags = AVIF_HASINDEX
        avihFramesPos = out.getFilePointer(); u32(0); // dwTotalFrames
        u32(0);                                      // dwInitialFrames
        u32(1);                                      // dwStreams
        avihBufferPos = out.getFilePointer(); u32(0); // dwSuggestedBufferSize
        u32(width); u32(height);
        u32(0); u32(0); u32(0); u32(0);              // dwReserved[4]
        // stream list
        fourcc("LIST"); long strlSizePos = out.getFilePointer(); u32(0); long strlStart = out.getFilePointer(); fourcc("strl");
        fourcc("strh"); u32(56);
        fourcc("vids"); fourcc("MJPG");
        u32(0);                                      // dwFlags
        u16(0); u16(0);                              // wPriority, wLanguage
        u32(0);                                      // dwInitialFrames
        u32(1000);                                   // dwScale
        u32((int) Math.round(fps * 1000));           // dwRate (rate/scale = fps)
        u32(0);                                      // dwStart
        strhLengthPos = out.getFilePointer(); u32(0); // dwLength
        strhBufferPos = out.getFilePointer(); u32(0); // dwSuggestedBufferSize
        u32(-1);                                     // dwQuality
        u32(0);                                      // dwSampleSize
        u16(0); u16(0); u16(width); u16(height);     // rcFrame
        fourcc("strf"); u32(40);                     // BITMAPINFOHEADER
        u32(40); u32(width); u32(height); u16(1); u16(24); fourcc("MJPG"); u32(width * height * 3);
        u32(0); u32(0); u32(0); u32(0);
        patch(strlSizePos, out.getFilePointer() - strlStart);
        patch(hdrlSizePos, out.getFilePointer() - hdrlStart);
        fourcc("LIST"); moviSizePos = out.getFilePointer(); u32(0); moviFourccPos = out.getFilePointer(); fourcc("movi");
    }

    public int width() { return width; }
    public int height() { return height; }
    public double fps() { return fps; }
    public int frameCount() { return frames; }

    /** Encodes and appends one frame; images of a different size are scaled to the video size. */
    public void addFrame(BufferedImage image) throws IOException {
        addJpeg(jpeg(image));
    }

    /** Appends an already encoded JPEG frame. */
    public void addJpeg(byte[] jpeg) throws IOException {
        if (closed) throw new IllegalStateException("closed");
        if (out.getFilePointer() + jpeg.length + 8 > MAX_BYTES) throw new IOException("AVI 1.0 size limit reached");
        long chunkPos = out.getFilePointer();
        fourcc("00dc"); u32(jpeg.length); out.write(jpeg);
        if ((jpeg.length & 1) == 1) out.write(0);
        ByteBuffer e = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        e.put("00dc".getBytes(StandardCharsets.US_ASCII)).putInt(0x10).putInt((int) (chunkPos - moviFourccPos)).putInt(jpeg.length);
        index.write(e.array());
        frames++;
        maxChunk = Math.max(maxChunk, jpeg.length);
    }

    /** JPEG bytes of {@code image} at this writer's size and quality. */
    public byte[] jpeg(BufferedImage image) throws IOException {
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB || image.getWidth() != width || image.getHeight() != height) {
            rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, width, height, null);
            g.dispose();
        }
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
            w.setOutput(ios);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            w.write(null, new IIOImage(rgb, null, null), p);
        } finally {
            w.dispose();
        }
        return bos.toByteArray();
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            patch(moviSizePos, out.getFilePointer() - moviFourccPos);
            fourcc("idx1"); u32(index.size()); out.write(index.toByteArray());
            patch(riffSizePos, out.getFilePointer() - 8);
            patch(avihFramesPos, frames);
            patch(strhLengthPos, frames);
            patch(avihBufferPos, maxChunk + 8);
            patch(strhBufferPos, maxChunk + 8);
        } finally {
            out.close();
        }
    }

    private void fourcc(String s) throws IOException { out.write(s.getBytes(StandardCharsets.US_ASCII), 0, 4); }
    private void u32(long v) throws IOException {
        out.write((int) v & 0xff); out.write((int) (v >> 8) & 0xff); out.write((int) (v >> 16) & 0xff); out.write((int) (v >> 24) & 0xff);
    }
    private void u16(int v) throws IOException { out.write(v & 0xff); out.write((v >> 8) & 0xff); }
    private void patch(long pos, long value) throws IOException {
        long here = out.getFilePointer();
        out.seek(pos); u32(value); out.seek(here);
    }
}
