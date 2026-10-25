package io.mosip.vivid.engine.image;

/**
 * A frame could not be decoded: wrong buffer size for its declared format, corrupt JPEG, unsupported format.
 * The orchestrator maps this to {@code FailureReason.INVALID_FRAME_DATA}.
 */
public class InvalidFrameException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public InvalidFrameException(String message) { super(message); }

    public InvalidFrameException(String message, Throwable cause) { super(message, cause); }
}
