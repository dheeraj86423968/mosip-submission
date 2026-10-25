package io.mosip.vivid.engine.onnx;

/** Engine-level failure (model missing, inference error). The orchestrator maps it to {@code FailureReason.ENGINE_ERROR}. */
public class EngineException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EngineException(String message) { super(message); }

    public EngineException(String message, Throwable cause) { super(message, cause); }
}
