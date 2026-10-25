package io.mosip.vivid.device.sbi;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkedInputStreamTest {
    @Test
    void decodesChunksWithExtensionsAndTrailers() throws IOException {
        String wire = "5;ext=1\r\nhello\r\n6\r\n world\r\n0\r\nX-Trailer: yes\r\n\r\n";
        ChunkedInputStream in = new ChunkedInputStream(new ByteArrayInputStream(wire.getBytes(StandardCharsets.ISO_8859_1)));
        assertThat(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1)).isEqualTo("hello world");
        assertThat(in.read()).isEqualTo(-1);
    }

    @Test
    void jpegDimensionsFromRealEncoderAndGarbage() throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(123, 45, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpeg", bos);
        assertThat(JpegDimensions.of(bos.toByteArray())).hasValueSatisfying(wh -> { assertThat(wh[0]).isEqualTo(123); assertThat(wh[1]).isEqualTo(45); });
        assertThat(JpegDimensions.of(new byte[] {1, 2, 3})).isEmpty();
        assertThat(JpegDimensions.of(java.util.Arrays.copyOf(bos.toByteArray(), 10))).isEmpty();
    }

    @Test
    void deliveryQueueDropsOldestAndPrioritisesHealth() throws InterruptedException {
        DeliveryQueue q = new DeliveryQueue(2);
        for (int i = 0; i < 3; i++) q.offer(io.mosip.vivid.device.FaceFrame.builder().sequence(i).width(1).height(1).format(io.mosip.vivid.device.PixelFormat.GRAY8).pixels(new byte[1]).build());
        q.offerHealth(io.mosip.vivid.device.DeviceHealth.connected());
        assertThat(q.dropped()).isEqualTo(1);
        assertThat(q.poll(10).health()).isNotNull();
        assertThat(q.poll(10).frame().sequence()).isEqualTo(1);
        assertThat(q.poll(10).frame().sequence()).isEqualTo(2);
        assertThat(q.poll(10)).isNull();
        q.close();
        assertThat(q.poll(10)).isNull();
    }
}
