package io.mosip.vivid.device;

/** Pixel layout of a {@link FaceFrame}. Engines must accept at least RGB888 and MJPEG. */
public enum PixelFormat {
    /** 3 bytes per pixel, row-major, no padding. */
    RGB888,
    /** 3 bytes per pixel, OpenCV native order. */
    BGR888,
    /** Android camera default: Y plane followed by interleaved VU. */
    NV21,
    /** Planar Y, U, V (I420). */
    YUV420,
    /** 8-bit single channel. */
    GRAY8,
    /** 16-bit single channel, used for IR and depth planes. */
    GRAY16,
    /** JPEG-compressed bytes, as delivered by the SBI stream endpoint. */
    MJPEG
}
