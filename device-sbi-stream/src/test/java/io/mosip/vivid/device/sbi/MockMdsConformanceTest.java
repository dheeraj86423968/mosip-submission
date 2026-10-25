package io.mosip.vivid.device.sbi;

import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.testkit.conformance.FrameSourceConformanceTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * testkit's frame-source contract against MOSIP's MockMDS (opt-in, {@code -Dvivid.interop.mockmds=<port>}).
 * The source scans the standard 4501-4600 range, exactly as in production.
 */
@EnabledIfSystemProperty(named = MockMdsInteropTest.PROPERTY, matches = "\\d+")
class MockMdsConformanceTest extends FrameSourceConformanceTest {
    @Override protected FaceFrameSource createSource() {
        return new SbiStreamFrameSource(SbiStreamConfig.builder().backoff(100, 1000).build());
    }

    @Override protected StreamOptions streamOptions() { return new StreamOptions(15, 4, 3000, 5); }

    @AfterEach void letMockMdsReleaseTheDevice() throws InterruptedException { Thread.sleep(300); }
}
