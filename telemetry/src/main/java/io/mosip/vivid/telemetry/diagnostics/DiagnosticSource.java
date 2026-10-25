package io.mosip.vivid.telemetry.diagnostics;

/** Anything that can produce a {@link DiagnosticSnapshot} on demand, e.g. a running liveness session. */
@FunctionalInterface
public interface DiagnosticSource {

    /** Captures the current diagnostic state. Must be cheap and must never include frames. */
    DiagnosticSnapshot captureDiagnostics();
}
