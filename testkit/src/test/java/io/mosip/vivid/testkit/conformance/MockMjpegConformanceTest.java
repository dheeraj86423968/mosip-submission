package io.mosip.vivid.testkit.conformance;

import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.mock.MockFaceFrameSource;
import io.mosip.vivid.device.mock.Pacing;
import io.mosip.vivid.device.mock.scenario.Scenarios;

/** Same contract, MJPEG output and unthrottled pacing. */
class MockMjpegConformanceTest extends FrameSourceConformanceTest {
    @Override protected FaceFrameSource createSource() {
        return MockFaceFrameSource.builder(Scenarios.bundled("live-blink-smile-turn")).format(PixelFormat.MJPEG).pacing(Pacing.unthrottled()).build();
    }
}
