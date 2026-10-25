package io.mosip.vivid.device.sbi;

import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.sbi.MockSbiServer;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import io.mosip.vivid.testkit.conformance.FrameSourceConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** testkit's frame-source contract against device-mock's SBI server, using MockMDS-style framing. */
class SbiStreamConformanceTest extends FrameSourceConformanceTest {
    private static MockSbiServer server;

    @BeforeAll static void startServer() {
        server = MockSbiServer.builder(Scenarios.bundled("live-pass")).port(0).fps(30)
                .framing(MockSbiServer.FramingStyle.MOCK_MDS).build().start();
    }

    @AfterAll static void closeServer() { if (server != null) server.close(); }

    @Override protected FaceFrameSource createSource() {
        return new SbiStreamFrameSource(SbiStreamConfig.builder().endpoint(SbiEndpoint.local(server.port())).backoff(100, 1000).build());
    }

    @Override protected StreamOptions streamOptions() { return new StreamOptions(15, 4, 3000, 5); }
}
