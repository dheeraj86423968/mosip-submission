package io.mosip.vivid.demo;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.SessionState.Hint;
import io.mosip.vivid.api.SessionState.Phase;
import io.mosip.vivid.api.Workflow;

import java.util.List;

/** User-facing strings, taken from docs/design/12-ux-spec.md and docs/design/08-error-handling.md (English). */
public final class UxCopy {
    private UxCopy() {}

    public static String prompt(ChallengeType t) {
        return switch (t) {
            case BLINK -> "Please blink";
            case BLINK_TWICE -> "Please blink twice";
            case SMILE -> "Please smile";
            case TURN_LEFT -> "Please turn your head to the left";
            case TURN_RIGHT -> "Please turn your head to the right";
            case LOOK_UP -> "Please look up";
            case LOOK_DOWN -> "Please look down";
            case NOD -> "Please nod";
            case OPEN_MOUTH -> "Please open your mouth";
            case RAISE_EYEBROWS -> "Please raise your eyebrows";
            case HOLD_STILL -> "Please hold still";
        };
    }

    public static String hint(Hint h) {
        return switch (h) {
            case MOVE_CLOSER -> "Move closer";
            case MOVE_BACK -> "Move back";
            case CENTER_FACE -> "Centre your face";
            case LOOK_AT_CAMERA -> "Look directly at the camera";
            case IMPROVE_LIGHTING -> "Improve lighting";
            case HOLD_STILL -> "Hold still";
            case ONLY_ONE_FACE -> "Only one face should be visible";
            case CHECKING_LIVENESS -> "Keep looking at the camera";
            case PLEASE_CONTINUE -> "Please continue";
            case ACTION_DETECTED -> "Action detected";
            case DEVICE_RECONNECTING -> "Reconnecting to device…";
            case DEVICE_DISCONNECTED -> "Device disconnected";
            default -> "";
        };
    }

    /** Headline for a non-challenge phase. */
    public static String phaseHeadline(Phase p, Workflow w) {
        return switch (p) {
            case IDLE -> "Start face capture";
            case DEVICE_CONNECTING -> "Connecting to the face capture device…";
            case FACE_ACQUIRING -> "Position your face in the oval";
            case PASSIVE_EVALUATING -> "Checking face liveness…";
            case ACTIVE_CHALLENGE -> "Follow the prompt";
            case PASSED -> w == Workflow.RESIDENT ? "Good, face captured successfully" : "Face verified";
            case FAILED -> "We could not verify face liveness. Please try again.";
            case LOCKED -> w == Workflow.RESIDENT ? "Face capture could not be completed. A supervisor is required to continue."
                    : "Face authentication is locked. Use another authentication method or contact your supervisor.";
            case CANCELLED -> "Cancelled";
        };
    }

    /** What the person in front of the camera is told when an attempt fails. Security-sensitive reasons stay generic. */
    public static String failureMessage(FailureReason r) {
        return switch (r) {
            case PAD_DETECTED, IDENTITY_DRIFT -> "Face verification could not be completed. Please try again.";
            case DEVICE_UNAVAILABLE -> "Face capture device not found. Check that the device is connected and switched on, then try again.";
            case DEVICE_CONNECTION_FAILURE -> "Lost connection to the face capture device. Check the cable and try again.";
            case CAMERA_UNAVAILABLE -> "The camera is not available. Close other applications that may be using it and try again.";
            case MULTIPLE_FACES -> "Only one face should be visible. Ask others to step out of the camera view.";
            case INVALID_FRAME_DATA -> "The face capture device sent unreadable images. Check the device and try again.";
            case ENGINE_ERROR -> "Face verification is temporarily unavailable. Please try again or contact support.";
            case SESSION_TIMEOUT -> "Face verification took too long. Please try again.";
            case CANCELLED, POLICY_DISABLED -> "";
            default -> "We could not verify face liveness. Please try again.";
        };
    }

    /** Up to three guidance lines for non-sensitive liveness failures. */
    public static List<String> guidance(FailureReason r) {
        return switch (r) {
            case ACTIVE_CHALLENGE_FAILED, CHALLENGE_TIMEOUT -> List.of("Follow the prompted action.", "Look directly at the camera.");
            case FACE_NOT_DETECTED, POOR_FACE_QUALITY -> List.of("Look directly at the camera.", "Ensure your face is clearly visible.", "Keep your face within the capture area.");
            case LIVENESS_BELOW_THRESHOLD -> List.of("Ensure your face is clearly visible.", "Improve lighting.", "Follow the prompted action.");
            default -> List.of();
        };
    }

    public static String signalLabel(String id) {
        return switch (id) {
            case "cnn_texture" -> "S1 CNN texture";
            case "moire" -> "S2 Moiré";
            case "color_stats" -> "S3 Colour stats";
            case "rppg" -> "S4 rPPG pulse";
            case "parallax" -> "S5 Parallax";
            case "challenge_timing" -> "S6 Challenge timing";
            case "identity" -> "S7 Identity";
            case "quality" -> "S9 Quality";
            case "injection" -> "Injection";
            default -> id;
        };
    }
}
