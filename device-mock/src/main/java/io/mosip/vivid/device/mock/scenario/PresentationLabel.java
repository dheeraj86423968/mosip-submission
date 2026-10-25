package io.mosip.vivid.device.mock.scenario;

/**
 * Ground-truth label of what a scenario segment presents to the camera, in ISO/IEC 30107-3 terms.
 * {@link #LIVE} is a bona fide presentation; every other value except {@link #NONE} is a presentation
 * attack instrument species (PAIS) and is used by the testkit to compute APCER per species.
 */
public enum PresentationLabel {
    /** Bona fide presentation: a live subject. */
    LIVE,
    /** Printed photograph (paper, matte or glossy). */
    PRINT,
    /** A photograph or video shown on a screen (phone, tablet, monitor). */
    SCREEN_REPLAY,
    /** A pre-recorded video of the subject replayed to the camera, ignoring prompts. */
    VIDEO_REPLAY,
    /** A 2D or 3D mask. */
    MASK,
    /** No subject in front of the camera (empty scene, cap on, etc.). Neither bona fide nor attack. */
    NONE;

    /** True for every presentation attack instrument species. */
    public boolean isAttack() { return this != LIVE && this != NONE; }

    /** True for a live subject. */
    public boolean isBonaFide() { return this == LIVE; }
}
