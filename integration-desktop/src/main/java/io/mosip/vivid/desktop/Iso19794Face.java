package io.mosip.vivid.desktop;

import io.mosip.vivid.desktop.stream.Jpeg;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Extracts the image data block from an ISO/IEC 19794-5:2011 face record (what SBI {@code rcapture} returns as
 * {@code bioValue} for {@code FACE_FULL FACE}). Only the fields needed to reach the image are parsed; everything
 * else is skipped by length. If the structured walk fails, the record is scanned for a JPEG / JPEG 2000 / PNG
 * signature as a fallback, because some SBIs pad or version the header differently.
 * <p>
 * The Registration Client uses {@code io.mosip.biometrics.util.face.FaceDecoder} (bio-utils) for the same job and
 * additionally transcodes JPEG 2000 to JPEG; the MOSIP patch injects that decoder into {@link CaptureBinding}. This
 * class exists so the standalone module (and its tests) do not depend on bio-utils.
 */
public final class Iso19794Face {
    private Iso19794Face() {}

    public enum ImageType { JPEG, JPEG2000, PNG, UNKNOWN }

    public static final class ImageRecord {
        public final ImageType type;
        public final int width;    // 0 if unknown
        public final int height;
        public final byte[] data;
        ImageRecord(ImageType type, int width, int height, byte[] data) { this.type = type; this.width = width; this.height = height; this.data = data; }
    }

    private static final int GENERAL_HEADER = 17;       // "FAC\0" "030\0" len(4) nReps(2) certFlag(1) temporal(2)

    public static boolean isFaceRecord(byte[] b) {
        return b != null && b.length > GENERAL_HEADER && b[0] == 'F' && b[1] == 'A' && b[2] == 'C' && b[3] == 0;
    }

    public static Optional<ImageRecord> extract(byte[] record) {
        if (record == null || record.length == 0) return Optional.empty();
        if (!isFaceRecord(record)) {
            return sniff(record);                      // raw image handed to us
        }
        try {
            String version = new String(record, 4, 3, StandardCharsets.US_ASCII);
            if (!"030".equals(version)) return sniff(record); // 2005 edition or vendor variant: fall back to scanning
            int p = GENERAL_HEADER;
            // Representation header
            int repLen = i32(record, p); p += 4;
            if (repLen <= 0 || p + repLen > record.length + 4) return sniff(record);
            p += 9;                                    // capture date/time
            p += 1 + 2 + 2;                            // device technology, vendor id, type id
            int qualityBlocks = u8(record, p); p += 1;
            p += qualityBlocks * 5;
            // Facial information block
            int landmarks = u16(record, p); p += 2;
            p += 1 + 1 + 1 + 1;                        // gender, eye colour, hair colour, subject height
            p += 3 + 2 + 3 + 3;                        // properties mask, expression, pose angles, pose uncertainty
            p += landmarks * 8;
            // Image information block
            p += 1;                                    // face image type
            int imageDataType = u8(record, p); p += 1;
            int width = u16(record, p); p += 2;
            int height = u16(record, p); p += 2;
            p += 1 + 2 + 1 + 1;                        // spatial sampling, post-acquisition, cross reference, colour space
            int imageLen = i32(record, p); p += 4;
            if (imageLen <= 0 || p + imageLen > record.length) return sniff(record);
            byte[] data = Arrays.copyOfRange(record, p, p + imageLen);
            ImageType type;
            switch (imageDataType) { case 0: type = ImageType.JPEG; break; case 1: case 2: type = ImageType.JPEG2000; break; case 3: type = ImageType.PNG; break; default: type = ImageType.UNKNOWN; }
            ImageType sniffed = sniffType(data);
            if (sniffed != ImageType.UNKNOWN) type = sniffed;      // trust the bytes over the declared type
            else if (type == ImageType.JPEG || type == ImageType.PNG) return sniff(record); // declared but not there
            if (type == ImageType.JPEG) { int[] d = Jpeg.dimensions(data); if (d.length == 2) { width = d[0]; height = d[1]; } }
            return Optional.of(new ImageRecord(type, width, height, data));
        } catch (IndexOutOfBoundsException truncated) {
            return sniff(record);
        }
    }

    /** Scan for a known image signature anywhere in the buffer and return the remainder. */
    static Optional<ImageRecord> sniff(byte[] b) {
        for (int i = 0; i + 12 <= b.length; i++) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xD8 && (b[i + 2] & 0xFF) == 0xFF) {
                byte[] data = Arrays.copyOfRange(b, i, b.length);
                int[] d = Jpeg.dimensions(data);
                return Optional.of(new ImageRecord(ImageType.JPEG, d.length == 2 ? d[0] : 0, d.length == 2 ? d[1] : 0, data));
            }
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 0x0C && b[i + 4] == 'j' && b[i + 5] == 'P') {
                return Optional.of(new ImageRecord(ImageType.JPEG2000, 0, 0, Arrays.copyOfRange(b, i, b.length)));
            }
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0x4F && (b[i + 2] & 0xFF) == 0xFF && (b[i + 3] & 0xFF) == 0x51) {
                return Optional.of(new ImageRecord(ImageType.JPEG2000, 0, 0, Arrays.copyOfRange(b, i, b.length)));
            }
            if ((b[i] & 0xFF) == 0x89 && b[i + 1] == 'P' && b[i + 2] == 'N' && b[i + 3] == 'G') {
                return Optional.of(new ImageRecord(ImageType.PNG, 0, 0, Arrays.copyOfRange(b, i, b.length)));
            }
        }
        return Optional.empty();
    }

    static ImageType sniffType(byte[] d) {
        if (Jpeg.isJpeg(d)) return ImageType.JPEG;
        if (Jpeg.isJpeg2000(d)) return ImageType.JPEG2000;
        if (Jpeg.isPng(d)) return ImageType.PNG;
        return ImageType.UNKNOWN;
    }

    /**
     * Builds a minimal, structurally valid ISO 19794-5:2011 record around an image. Used by tests and by the mock
     * device; real records come from the SBI.
     */
    public static byte[] wrap(byte[] image, int imageDataType, int width, int height) {
        int facialInfo = 2 + 4 + 3 + 2 + 3 + 3;
        int imageInfo = 1 + 1 + 2 + 2 + 1 + 2 + 1 + 1;
        int repLen = 4 + 9 + 5 + 1 + facialInfo + imageInfo + 4 + image.length;
        int total = GENERAL_HEADER + repLen;
        byte[] out = new byte[total];
        int p = 0;
        out[p++] = 'F'; out[p++] = 'A'; out[p++] = 'C'; out[p++] = 0;
        out[p++] = '0'; out[p++] = '3'; out[p++] = '0'; out[p++] = 0;
        p = put32(out, p, total); p = put16(out, p, 1); out[p++] = 0; p = put16(out, p, 0);
        p = put32(out, p, repLen);
        p += 9;                                 // datetime zeros
        out[p++] = 0; p = put16(out, p, 0); p = put16(out, p, 0);
        out[p++] = 0;                           // quality blocks
        p = put16(out, p, 0);                   // landmarks
        p += 4; p += 3; p += 2; p += 3; p += 3;
        out[p++] = 1;                           // face image type: full frontal
        out[p++] = (byte) imageDataType;
        p = put16(out, p, width); p = put16(out, p, height);
        out[p++] = 0; p = put16(out, p, 0); out[p++] = 0; out[p++] = 0;
        p = put32(out, p, image.length);
        System.arraycopy(image, 0, out, p, image.length);
        return out;
    }

    private static int u8(byte[] b, int p) { return b[p] & 0xFF; }
    private static int u16(byte[] b, int p) { return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF); }
    private static int i32(byte[] b, int p) { return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF); }
    private static int put16(byte[] b, int p, int v) { b[p] = (byte) (v >>> 8); b[p + 1] = (byte) v; return p + 2; }
    private static int put32(byte[] b, int p, int v) { b[p] = (byte) (v >>> 24); b[p + 1] = (byte) (v >>> 16); b[p + 2] = (byte) (v >>> 8); b[p + 3] = (byte) v; return p + 4; }
}
