package io.mosip.vivid.core.testing;

import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;

/** Builds tiny placeholder {@link FaceFrame}s; the fake analyzer ignores their pixels. */
public final class Frames {

    private Frames() {}

    private static long sequence;

    /** A 2x2 RGB frame with the given timestamp. */
    public static FaceFrame frame(long timestampNanos) {
        return FaceFrame.builder().sequence(++sequence).timestampNanos(timestampNanos).width(2).height(2)
                .format(PixelFormat.RGB888).pixels(new byte[12]).build();
    }

    /** A frame with an arbitrary timestamp. */
    public static FaceFrame frame() {
        return frame(0);
    }
}
