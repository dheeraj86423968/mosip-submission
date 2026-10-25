// Maps liveness-api enum names (SessionState.Phase / Hint, ChallengeType, FailureReason) to the
// shared i18n keys used by both Registration Clients (desktop ResourceBundle and Flutter ARB).
//
// Canonical key names are dotted (`liveness.hint.checking`). ARB identifiers cannot contain dots,
// so the Flutter side stores them as `liveness_hint_checking`; [LivenessMessages.arbKey] does the
// conversion and [LivenessMessages.text] resolves through AppLocalizations by name so that this
// file does not need to change when keys are added.
import 'package:flutter/widgets.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';

class LivenessMessages {
  LivenessMessages._();

  /// Dotted key for a SessionState.Hint name (e.g. MOVE_CLOSER -> liveness.hint.move_closer).
  static String hintKey(String hint) {
    switch (hint) {
      case 'NONE':
        return 'liveness.hint.none';
      case 'MOVE_CLOSER':
        return 'liveness.hint.move_closer';
      case 'MOVE_BACK':
        return 'liveness.hint.move_back';
      case 'CENTER_FACE':
        return 'liveness.hint.center_face';
      case 'LOOK_AT_CAMERA':
        return 'liveness.hint.look_at_camera';
      case 'IMPROVE_LIGHTING':
        return 'liveness.hint.improve_lighting';
      case 'HOLD_STILL':
        return 'liveness.hint.hold_still';
      case 'ONLY_ONE_FACE':
        return 'liveness.hint.only_one_face';
      case 'CHECKING_LIVENESS':
        return 'liveness.hint.checking';
      case 'PLEASE_CONTINUE':
        return 'liveness.hint.please_continue';
      case 'ACTION_DETECTED':
        return 'liveness.hint.action_detected';
      case 'CAPTURED_OK':
        return 'liveness.hint.captured_ok';
      case 'TRY_AGAIN':
        return 'liveness.hint.try_again';
      case 'RETRY_AVAILABLE':
        return 'liveness.hint.retry_available';
      case 'MAX_RETRIES_REACHED':
        return 'liveness.hint.max_retries_reached';
      case 'DEVICE_RECONNECTING':
        return 'liveness.hint.device_reconnecting';
      case 'DEVICE_DISCONNECTED':
        return 'liveness.hint.device_disconnected';
      default:
        return 'liveness.hint.none';
    }
  }

  /// Dotted key for a ChallengeType name (e.g. TURN_LEFT -> liveness.challenge.turn_left).
  static String challengeKey(String type) => 'liveness.challenge.${type.toLowerCase()}';

  /// Dotted key for a SessionState.Phase name used in the status line.
  static String phaseKey(String phase) => 'liveness.phase.${phase.toLowerCase()}';

  /// Failure key. Security-sensitive reasons (PAD, identity drift) collapse to the generic PAD
  /// message so the UI never tells an attacker which signal caught them.
  static String failureKey(String? reason, {bool securitySensitive = false}) {
    if (reason == null) return 'liveness.failure.generic';
    if (securitySensitive || reason == 'PAD_DETECTED' || reason == 'IDENTITY_DRIFT') {
      return 'liveness.failure.pad_generic';
    }
    switch (reason) {
      case 'DEVICE_UNAVAILABLE':
      case 'DEVICE_CONNECTION_FAILURE':
      case 'CAMERA_UNAVAILABLE':
        return 'liveness.failure.device';
      case 'FACE_NOT_DETECTED':
        return 'liveness.failure.face_not_detected';
      case 'MULTIPLE_FACES':
        return 'liveness.failure.multiple_faces';
      case 'POOR_FACE_QUALITY':
        return 'liveness.failure.poor_quality';
      case 'LIVENESS_BELOW_THRESHOLD':
        return 'liveness.failure.below_threshold';
      case 'ACTIVE_CHALLENGE_FAILED':
        return 'liveness.failure.challenge_failed';
      case 'CHALLENGE_TIMEOUT':
        return 'liveness.failure.challenge_timeout';
      case 'MAX_RETRIES_EXCEEDED':
        return 'liveness.failure.max_retries';
      case 'SESSION_TIMEOUT':
        return 'liveness.failure.session_timeout';
      case 'CANCELLED':
        return 'liveness.failure.cancelled';
      case 'POLICY_DISABLED':
        return 'liveness.failure.policy_disabled';
      case 'INVALID_FRAME_DATA':
      case 'ENGINE_ERROR':
        return 'liveness.failure.engine';
      default:
        return 'liveness.failure.generic';
    }
  }

  /// `liveness.hint.checking` -> `liveness_hint_checking` (ARB identifier).
  static String arbKey(String dottedKey) => dottedKey.replaceAll('.', '_');

  /// Resolves a dotted key through the generated AppLocalizations. Falls back to the key itself
  /// so a missing translation is visible rather than blank.
  static String text(BuildContext context, String dottedKey) {
    final loc = AppLocalizations.of(context);
    if (loc == null) return dottedKey;
    return _lookup(loc, arbKey(dottedKey)) ?? dottedKey;
  }

  // gen_l10n produces one getter per key; without reflection we keep an explicit table of the
  // keys this overlay uses. Add a line here whenever a key is added to liveness_en.arb.
  static String? _lookup(AppLocalizations l, String k) {
    final table = <String, String Function()>{
      'liveness_title': () => l.liveness_title,
      'liveness_hint_none': () => l.liveness_hint_none,
      'liveness_hint_move_closer': () => l.liveness_hint_move_closer,
      'liveness_hint_move_back': () => l.liveness_hint_move_back,
      'liveness_hint_center_face': () => l.liveness_hint_center_face,
      'liveness_hint_look_at_camera': () => l.liveness_hint_look_at_camera,
      'liveness_hint_improve_lighting': () => l.liveness_hint_improve_lighting,
      'liveness_hint_hold_still': () => l.liveness_hint_hold_still,
      'liveness_hint_only_one_face': () => l.liveness_hint_only_one_face,
      'liveness_hint_checking': () => l.liveness_hint_checking,
      'liveness_hint_please_continue': () => l.liveness_hint_please_continue,
      'liveness_hint_action_detected': () => l.liveness_hint_action_detected,
      'liveness_hint_captured_ok': () => l.liveness_hint_captured_ok,
      'liveness_hint_try_again': () => l.liveness_hint_try_again,
      'liveness_hint_retry_available': () => l.liveness_hint_retry_available,
      'liveness_hint_max_retries_reached': () => l.liveness_hint_max_retries_reached,
      'liveness_hint_device_reconnecting': () => l.liveness_hint_device_reconnecting,
      'liveness_hint_device_disconnected': () => l.liveness_hint_device_disconnected,
      'liveness_phase_idle': () => l.liveness_phase_idle,
      'liveness_phase_device_connecting': () => l.liveness_phase_device_connecting,
      'liveness_phase_face_acquiring': () => l.liveness_phase_face_acquiring,
      'liveness_phase_passive_evaluating': () => l.liveness_phase_passive_evaluating,
      'liveness_phase_active_challenge': () => l.liveness_phase_active_challenge,
      'liveness_phase_passed': () => l.liveness_phase_passed,
      'liveness_phase_failed': () => l.liveness_phase_failed,
      'liveness_phase_locked': () => l.liveness_phase_locked,
      'liveness_phase_cancelled': () => l.liveness_phase_cancelled,
      'liveness_challenge_blink': () => l.liveness_challenge_blink,
      'liveness_challenge_blink_twice': () => l.liveness_challenge_blink_twice,
      'liveness_challenge_smile': () => l.liveness_challenge_smile,
      'liveness_challenge_open_mouth': () => l.liveness_challenge_open_mouth,
      'liveness_challenge_raise_eyebrows': () => l.liveness_challenge_raise_eyebrows,
      'liveness_challenge_turn_left': () => l.liveness_challenge_turn_left,
      'liveness_challenge_turn_right': () => l.liveness_challenge_turn_right,
      'liveness_challenge_look_up': () => l.liveness_challenge_look_up,
      'liveness_challenge_look_down': () => l.liveness_challenge_look_down,
      'liveness_challenge_nod': () => l.liveness_challenge_nod,
      'liveness_challenge_hold_still': () => l.liveness_challenge_hold_still,
      // Spoken-code challenge (liveness-av): placeholder message, the overlay fills in the spaced digits.
      'liveness_challenge_speak_code': () => l.liveness_challenge_speak_code('{code}'),
      'liveness_failure_generic': () => l.liveness_failure_generic,
      'liveness_failure_pad_generic': () => l.liveness_failure_pad_generic,
      'liveness_failure_device': () => l.liveness_failure_device,
      'liveness_failure_face_not_detected': () => l.liveness_failure_face_not_detected,
      'liveness_failure_multiple_faces': () => l.liveness_failure_multiple_faces,
      'liveness_failure_poor_quality': () => l.liveness_failure_poor_quality,
      'liveness_failure_below_threshold': () => l.liveness_failure_below_threshold,
      'liveness_failure_challenge_failed': () => l.liveness_failure_challenge_failed,
      'liveness_failure_challenge_timeout': () => l.liveness_failure_challenge_timeout,
      'liveness_failure_max_retries': () => l.liveness_failure_max_retries,
      'liveness_failure_session_timeout': () => l.liveness_failure_session_timeout,
      'liveness_failure_cancelled': () => l.liveness_failure_cancelled,
      'liveness_failure_policy_disabled': () => l.liveness_failure_policy_disabled,
      'liveness_failure_engine': () => l.liveness_failure_engine,
      'liveness_failure_locked': () => l.liveness_failure_locked,
      'liveness_button_retry': () => l.liveness_button_retry,
      'liveness_button_cancel': () => l.liveness_button_cancel,
      'liveness_button_continue': () => l.liveness_button_continue,
      'liveness_button_supervisor': () => l.liveness_button_supervisor,
      // Placeholder message: gen_l10n emits a method; return the raw template, the overlay fills it in.
      'liveness_attempt_of': () => l.liveness_attempt_of('{attempt}', '{max}'),
      'liveness_device_chip': () => l.liveness_device_chip,
      'liveness_offline_chip': () => l.liveness_offline_chip,
      'liveness_assisted_mode': () => l.liveness_assisted_mode,
      'liveness_face_match_checking': () => l.liveness_face_match_checking,
      'liveness_face_match_failed': () => l.liveness_face_match_failed,
      'liveness_face_match_unavailable': () => l.liveness_face_match_unavailable,
      'liveness_face_match_no_enrolment': () => l.liveness_face_match_no_enrolment,
      'liveness_capture_binding_failed': () => l.liveness_capture_binding_failed,
    };
    return table[k]?.call();
  }
}
