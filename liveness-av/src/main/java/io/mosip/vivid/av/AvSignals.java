package io.mosip.vivid.av;

/** Signal id reported by {@link AvFrameAnalyzer}. Not in the frozen {@code Signals} class; fusion weight defaults to 1.0. */
public final class AvSignals {
    private AvSignals() {}
    /** Spoken-code audio-visual liveness: code match plus lip-audio synchrony. */
    public static final String AV_SYNC = "av_sync";
}
