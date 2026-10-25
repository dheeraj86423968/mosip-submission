package io.mosip.vivid.api;

/**
 * SPI for a liveness/PAD engine. Engines score frames and extract face geometry; they do not decide.
 * Decisions, policy, retries, challenge selection and action verification live in the orchestrator (liveness-core),
 * so every engine behaves identically from the Registration Client's point of view.
 */
public interface LivenessEngine extends AutoCloseable {
    EngineInfo info();

    /** Create a fresh analyzer for one session. Cheap; models are shared and already loaded. */
    FrameAnalyzer newAnalyzer();

    /** Run the packaged self-test (known live and attack frames must score as expected). Used at startup and after model updates. */
    default SelfTestResult selfTest() { return SelfTestResult.skipped("engine provides no self-test"); }

    @Override default void close() {}

    final class SelfTestResult {
        public final boolean passed; public final boolean skipped; public final String detail;
        private SelfTestResult(boolean passed, boolean skipped, String detail) { this.passed = passed; this.skipped = skipped; this.detail = detail; }
        public static SelfTestResult pass(String d) { return new SelfTestResult(true, false, d); }
        public static SelfTestResult fail(String d) { return new SelfTestResult(false, false, d); }
        public static SelfTestResult skipped(String d) { return new SelfTestResult(true, true, d); }
    }
}
