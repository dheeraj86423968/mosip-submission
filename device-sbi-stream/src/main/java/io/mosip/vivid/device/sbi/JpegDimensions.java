package io.mosip.vivid.device.sbi;

import java.util.Optional;

/** Reads width/height from a JPEG's SOF marker without decoding the image. */
public final class JpegDimensions {
    private JpegDimensions() {}

    /** Width and height, or empty if the bytes are not a JPEG with a readable frame header. */
    public static Optional<int[]> of(byte[] b) {
        if (b == null || b.length < 4 || (b[0] & 0xff) != 0xFF || (b[1] & 0xff) != 0xD8) return Optional.empty();
        int i = 2;
        while (i + 3 < b.length) {
            if ((b[i] & 0xff) != 0xFF) { i++; continue; }
            int marker = b[i + 1] & 0xff;
            if (marker == 0xFF) { i++; continue; }                 // padding
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) { i += 2; continue; }  // no payload
            if (marker == 0xD9 || marker == 0xDA) return Optional.empty();   // EOI / SOS before SOF
            if (i + 3 >= b.length) return Optional.empty();
            int len = ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
            boolean sof = (marker >= 0xC0 && marker <= 0xCF) && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                if (i + 8 >= b.length) return Optional.empty();
                int h = ((b[i + 5] & 0xff) << 8) | (b[i + 6] & 0xff);
                int w = ((b[i + 7] & 0xff) << 8) | (b[i + 8] & 0xff);
                return (w > 0 && h > 0) ? Optional.of(new int[] {w, h}) : Optional.empty();
            }
            i += 2 + len;
        }
        return Optional.empty();
    }
}
