package io.mosip.vivid.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AviMjpegWriterTest {

    private static BufferedImage solid(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static String fourcc(ByteBuffer b, int pos) {
        byte[] f = new byte[4];
        for (int i = 0; i < 4; i++) f[i] = b.get(pos + i);
        return new String(f, StandardCharsets.US_ASCII);
    }

    private static int find(ByteBuffer b, String tag, int from) {
        byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = from; i <= b.limit() - 4; i++) {
            for (int j = 0; j < 4; j++) if (b.get(i + j) != t[j]) continue outer;
            return i;
        }
        return -1;
    }

    @Test
    void writesAValidRiffAviWithIndexAndDecodableFrames(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("t.avi");
        Color[] colors = {Color.RED, Color.GREEN, Color.BLUE};
        try (AviMjpegWriter w = new AviMjpegWriter(file, 160, 90, 5, 0.9f)) {
            for (Color c : colors) w.addFrame(solid(160, 90, c));
            w.addFrame(solid(320, 180, Color.WHITE));   // scaled to the video size
            assertThat(w.frameCount()).isEqualTo(4);
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(fourcc(b, 0)).isEqualTo("RIFF");
        assertThat(b.getInt(4)).isEqualTo(bytes.length - 8);
        assertThat(fourcc(b, 8)).isEqualTo("AVI ");
        assertThat(fourcc(b, 12)).isEqualTo("LIST");
        assertThat(fourcc(b, 20)).isEqualTo("hdrl");

        int avih = find(b, "avih", 0);
        assertThat(b.getInt(avih + 4)).isEqualTo(56);
        assertThat(b.getInt(avih + 8)).isEqualTo(200_000);       // 5 fps
        assertThat(b.getInt(avih + 8 + 12) & 0x10).isEqualTo(0x10); // AVIF_HASINDEX
        assertThat(b.getInt(avih + 8 + 16)).isEqualTo(4);        // total frames
        assertThat(b.getInt(avih + 8 + 32)).isEqualTo(160);
        assertThat(b.getInt(avih + 8 + 36)).isEqualTo(90);

        int strh = find(b, "strh", 0);
        assertThat(fourcc(b, strh + 8)).isEqualTo("vids");
        assertThat(fourcc(b, strh + 12)).isEqualTo("MJPG");
        assertThat(b.getInt(strh + 8 + 24) / (double) b.getInt(strh + 8 + 20)).isEqualTo(5.0);
        assertThat(b.getInt(strh + 8 + 32)).isEqualTo(4);        // dwLength

        int movi = find(b, "movi", 0);
        int moviSize = b.getInt(movi - 4);
        int idx1 = find(b, "idx1", movi);
        assertThat(movi + moviSize).isEqualTo(idx1);
        int entries = b.getInt(idx1 + 4) / 16;
        assertThat(entries).isEqualTo(4);
        assertThat(idx1 + 8 + entries * 16).isEqualTo(bytes.length);

        int[] expected = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF};
        for (int i = 0; i < entries; i++) {
            int e = idx1 + 8 + i * 16;
            assertThat(fourcc(b, e)).isEqualTo("00dc");
            assertThat(b.getInt(e + 4)).isEqualTo(0x10);
            int chunk = movi + b.getInt(e + 8);
            assertThat(fourcc(b, chunk)).isEqualTo("00dc");
            int size = b.getInt(chunk + 4);
            assertThat(size).isEqualTo(b.getInt(e + 12));
            BufferedImage frame = ImageIO.read(new ByteArrayInputStream(bytes, chunk + 8, size));
            assertThat(frame.getWidth()).isEqualTo(160);
            assertThat(frame.getHeight()).isEqualTo(90);
            int px = frame.getRGB(80, 45) & 0xFFFFFF;
            for (int s = 0; s < 24; s += 8) {
                assertThat(Math.abs(((px >> s) & 0xFF) - ((expected[i] >> s) & 0xFF))).isLessThan(24);
            }
        }
    }

    @Test
    void oddSizedChunksArePaddedToWordBoundaries(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("odd.avi");
        try (AviMjpegWriter w = new AviMjpegWriter(file, 8, 8, 1, 1f)) {
            w.addJpeg(new byte[]{1, 2, 3});
            w.addJpeg(new byte[]{4, 5, 6, 7});
        }
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        int movi = find(b, "movi", 0);
        assertThat(fourcc(b, movi + 4)).isEqualTo("00dc");
        assertThat(fourcc(b, movi + 4 + 8 + 4)).isEqualTo("00dc");   // 3 bytes + 1 pad
        int idx1 = find(b, "idx1", movi);
        assertThat(b.getInt(idx1 + 8 + 8)).isEqualTo(4);
        assertThat(b.getInt(idx1 + 8 + 16 + 8)).isEqualTo(16);
    }

    @Test
    void rejectsBadArgumentsAndUseAfterClose(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> new AviMjpegWriter(dir.resolve("a.avi"), 0, 10, 5, 0.5f)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AviMjpegWriter(dir.resolve("a.avi"), 10, 10, 0, 0.5f)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AviMjpegWriter(dir.resolve("a.avi"), 10, 10, 5, 0f)).isInstanceOf(IllegalArgumentException.class);
        AviMjpegWriter w = new AviMjpegWriter(dir.resolve("b.avi"), 10, 10, 5, 0.5f);
        w.close();
        w.close();   // idempotent
        assertThatThrownBy(() -> w.addJpeg(new byte[]{1})).isInstanceOf(IllegalStateException.class);
    }
}
