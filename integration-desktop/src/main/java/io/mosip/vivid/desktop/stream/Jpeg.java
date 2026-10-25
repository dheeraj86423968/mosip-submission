package io.mosip.vivid.desktop.stream;

/** Minimal JPEG / JPEG 2000 header helpers; no decoding. */
public final class Jpeg {
    private Jpeg() {}

    public static final int[] NO_DIMENSIONS = new int[0];

    public static boolean isJpeg(byte[] b) {
        return b != null && b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    /** JP2 signature box or raw J2K codestream. */
    public static boolean isJpeg2000(byte[] b) {
        if (b == null || b.length < 12) return false;
        boolean jp2 = b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0x0C && b[4] == 'j' && b[5] == 'P' && b[6] == ' ' && b[7] == ' ';
        boolean j2k = (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0x4F && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x51;
        return jp2 || j2k;
    }

    public static boolean isPng(byte[] b) {
        return b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    /**
     * Width/height from the first SOFn marker, or {@link #NO_DIMENSIONS}. Walks marker segments so it is not fooled
     * by APPn thumbnails that embed a second JPEG.
     */
    public static int[] dimensions(byte[] b) {
        if (!isJpeg(b)) return NO_DIMENSIONS;
        int i = 2;
        while (i + 3 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) { i++; continue; }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) { i++; continue; }           // fill byte
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) { i += 2; continue; } // no length
            if (marker == 0xD9 || marker == 0xDA) return NO_DIMENSIONS; // EOI / SOS before SOF
            if (i + 3 >= b.length) return NO_DIMENSIONS;
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            boolean sof = (marker >= 0xC0 && marker <= 0xCF) && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                if (i + 8 >= b.length) return NO_DIMENSIONS;
                int height = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int width = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                return width > 0 && height > 0 ? new int[] { width, height } : NO_DIMENSIONS;
            }
            i += 2 + len;
        }
        return NO_DIMENSIONS;
    }
}
