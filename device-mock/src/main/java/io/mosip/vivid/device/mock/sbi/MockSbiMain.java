package io.mosip.vivid.device.mock.sbi;

import io.mosip.vivid.device.mock.scenario.Scenario;
import io.mosip.vivid.device.mock.scenario.Scenarios;

import java.util.Locale;

/**
 * Command-line entry point: {@code java -jar device-mock.jar --port 4501 --scenario live-pass --fps 15}.
 * <pre>
 *   --port N              port on 127.0.0.1 (default 4501; 0 = ephemeral)
 *   --scenario NAME|FILE  bundled scenario name or path to a scenario JSON (default live-blink-smile-turn)
 *   --fps N               override scenario fps (default: scenario's)
 *   --framing standard|mock-mds   multipart framing style (default standard)
 *   --certification L0|L1 (default L0)
 *   --clock start|first-stream    when scenario time starts (default first-stream)
 *   --list                list bundled scenarios and exit
 * </pre>
 */
public final class MockSbiMain {
    private MockSbiMain() {}

    public static void main(String[] args) throws Exception {
        int port = 4501; String scenarioRef = "live-blink-smile-turn"; double fps = 0;
        MockSbiServer.FramingStyle framing = MockSbiServer.FramingStyle.STANDARD;
        String certification = "L0"; boolean anchorOnStart = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--scenario" -> scenarioRef = args[++i];
                case "--fps" -> fps = Double.parseDouble(args[++i]);
                case "--framing" -> framing = MockSbiServer.FramingStyle.valueOf(args[++i].toUpperCase(Locale.ROOT).replace('-', '_'));
                case "--certification" -> certification = args[++i];
                case "--clock" -> anchorOnStart = "start".equalsIgnoreCase(args[++i]);
                case "--list" -> { Scenarios.bundledNames().forEach(System.out::println); return; }
                case "--help", "-h" -> { usage(); return; }
                default -> { System.err.println("unknown argument " + args[i]); usage(); System.exit(2); }
            }
        }
        Scenario scenario = Scenarios.resolve(scenarioRef);
        MockSbiServer server = MockSbiServer.builder(scenario).port(port).fps(fps).framing(framing)
                .certification(certification).anchorClockOnStart(anchorOnStart).build().start();
        System.out.println("Mock SBI face device listening on " + server.baseUrl());
        System.out.println("  scenario : " + scenario.name() + " (" + scenario.description() + ")");
        System.out.println("  verbs    : MOSIPDISC /device, MOSIPDINFO /info, STREAM /stream, RCAPTURE|CAPTURE /capture");
        System.out.println("  admin    : POST /mock/fault, POST /mock/scenario, GET /mock/status");
        System.out.println("Press Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }

    private static void usage() {
        System.out.println("usage: MockSbiMain [--port N] [--scenario NAME|FILE] [--fps N] [--framing standard|mock-mds] [--certification L0|L1] [--clock start|first-stream] [--list]");
    }
}
