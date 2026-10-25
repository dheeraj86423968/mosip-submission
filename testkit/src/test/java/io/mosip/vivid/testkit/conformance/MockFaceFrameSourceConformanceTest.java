package io.mosip.vivid.testkit.conformance;

import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.mock.MockFaceFrameSource;
import io.mosip.vivid.device.mock.Pacing;
import io.mosip.vivid.device.mock.scenario.Scenarios;

/** The mock device must itself pass the adapter contract (real-time pacing, RGB888). */
class MockFaceFrameSourceConformanceTest extends FrameSourceConformanceTest {
    @Override protected FaceFrameSource createSource() {
        return MockFaceFrameSource.builder(Scenarios.bundled("live-pass")).pacing(Pacing.speed(4)).build();
    }
}
