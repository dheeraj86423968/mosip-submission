package io.mosip.vivid.api;

/** Canonical signal identifiers so policy weights, audit and engines agree on names. */
public final class Signals {
    private Signals() {}
    public static final String CNN_TEXTURE = "cnn_texture";        // S1
    public static final String MOIRE = "moire";                    // S2
    public static final String COLOR_STATS = "color_stats";        // S3
    public static final String RPPG = "rppg";                      // S4
    public static final String PARALLAX = "parallax";              // S5
    public static final String CHALLENGE_TIMING = "challenge_timing"; // S6 (computed by orchestrator)
    public static final String IDENTITY = "identity";              // S7 (computed by orchestrator from embeddings)
    public static final String DEPTH = "depth";                    // S8
    public static final String IR = "ir";                          // S8
    public static final String QUALITY = "quality";                // S9 (gate, not an attack signal)
    public static final String INJECTION = "injection";            // virtual camera / replay detection

    public static final String ATTACK_PRINT = "PRINT";
    public static final String ATTACK_SCREEN = "SCREEN_REPLAY";
    public static final String ATTACK_VIDEO = "VIDEO_REPLAY";
    public static final String ATTACK_MASK = "MASK";
    public static final String ATTACK_DEEPFAKE = "DEEPFAKE";
    public static final String ATTACK_INJECTION = "INJECTION";
    public static final String ATTACK_UNKNOWN = "UNKNOWN";
}
