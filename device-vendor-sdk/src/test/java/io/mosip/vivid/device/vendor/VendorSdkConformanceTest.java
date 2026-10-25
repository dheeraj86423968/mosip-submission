package io.mosip.vivid.device.vendor;

import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.testkit.conformance.FrameSourceConformanceTest;

/** testkit's frame-source contract against the template adapter over {@link FakeVendorSdk}. */
class VendorSdkConformanceTest extends FrameSourceConformanceTest {
    @Override protected FaceFrameSource createSource() {
        return new VendorSdkFrameSource("fake-vendor", new FakeVendorSdk(320, 240, 30), new AbstractFrameSourceAdapter.Backoff(20, 200));
    }

    @Override protected StreamOptions streamOptions() { return new StreamOptions(0, 4, 1000, 3); }
}
