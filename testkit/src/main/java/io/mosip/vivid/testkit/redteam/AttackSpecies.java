package io.mosip.vivid.testkit.redteam;

import java.util.Locale;

/**
 * The red-team species. Every species is a <b>digital simulation</b> applied to device-mock's drawn synthetic face;
 * none is a real presentation attack instrument, and the {@code SIM_} prefix keeps them apart from the corpus-ethics
 * §5 species. The models follow {@code tools/proxy-eval/simulate_attacks.py} in reduced form (see
 * {@link RedTeamRenderer}).
 */
public enum AttackSpecies {
    /** A live synthetic session: blinks, a small head turn, optional slight smile. */
    BONA_FIDE("BONA_FIDE", "NONE"),
    /** One still frame printed (clustered-dot halftone or ordered dither on paper), hand-held, no facial motion. */
    SIM_PRINT("SIM_PRINT", "PRINT"),
    /** The same print bent around a vertical axis, flexing slowly. */
    SIM_PRINT_WARPED("SIM_PRINT_WARPED", "PRINT"),
    /** The same print with both eyes cut out; the subject's own (blinking) eyes show through the holes. */
    SIM_PRINT_CUTOUT("SIM_PRINT_CUTOUT", "PRINT"),
    /** Every live frame shown on an RGB-stripe display and re-captured: moiré, gamma, white point, glare, banding. */
    SIM_SCREEN_REPLAY("SIM_SCREEN_REPLAY", "VIDEO_REPLAY"),
    /** One still frame on the same simulated display. */
    SIM_SCREEN_PHOTO("SIM_SCREEN_PHOTO", "SCREEN_REPLAY"),
    /** Replay on a fine-pitch display with playback-rate error, dropped/duplicated frames and timestamp jitter. */
    SIM_REPLAY_JITTER("SIM_REPLAY_JITTER", "VIDEO_REPLAY"),
    /** A low-resolution, heavily compressed recording replayed full frame and re-captured out of focus. */
    SIM_LOWRES_REPLAY("SIM_LOWRES_REPLAY", "VIDEO_REPLAY");

    private final String label;
    private final String paiClass;

    AttackSpecies(String label, String paiClass) { this.label = label; this.paiClass = paiClass; }

    /** Label written to capture-log.csv and scores.jsonl (and the APCER species key). */
    public String label() { return label; }

    /** Engine-level attack class ({@code PRINT}, {@code SCREEN_REPLAY}, {@code VIDEO_REPLAY}) or {@code NONE}. */
    public String paiClass() { return paiClass; }

    public boolean isAttack() { return this != BONA_FIDE; }

    public static AttackSpecies parse(String s) { return valueOf(s.trim().toUpperCase(Locale.ROOT)); }
}
