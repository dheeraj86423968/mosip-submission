package io.mosip.vivid.device.mock.scenario;

import java.awt.image.BufferedImage;

/** Produces the image for a segment at a given segment-relative time. Implementations must be thread-safe. */
public interface FrameGenerator {
    /**
     * @param segmentTimeMs milliseconds since the segment started (restarts at 0 on each loop)
     * @param frameIndex    0-based index of the frame within the segment
     */
    BufferedImage render(long segmentTimeMs, long frameIndex);
}
