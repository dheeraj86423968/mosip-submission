package io.mosip.vivid.device;

/** Receives frames and health changes from a {@link FaceFrameSource}. Called on the source's delivery thread; must return quickly. */
public interface FrameSink {
    void onFrame(FaceFrame frame);
    default void onHealth(DeviceHealth health) {}
    /** Called when the source stops delivering permanently (after {@link FaceFrameSource#stop()} or an unrecoverable failure). */
    default void onEnd() {}
}
