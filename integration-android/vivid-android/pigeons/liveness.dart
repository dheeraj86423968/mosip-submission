// Pigeon definition for the Vivid liveness bridge.
//
// Generate (from the android-registration-client repo root, after copying this file to
// pigeon/liveness.dart and the vivid-android module to android/vivid-android):
//
//   dart run pigeon --input pigeon/liveness.dart \
//     --dart_out lib/pigeon/liveness_pigeon.dart \
//     --kotlin_out android/vivid-android/src/main/kotlin/io/mosip/vivid/android/bridge/gen/LivenessPigeon.kt \
//     --kotlin_package io.mosip.vivid.android.bridge.gen
//
// Pigeon ^10 (as pinned by the registration client's pubspec) emits `LivenessHostApi` (interface,
// `LivenessHostApi.setUp(binaryMessenger, api)`) and `LivenessFlutterApi` (class with
// `onState(sessionId, state) { }` callback) in Kotlin, and `LivenessHostApi` / `LivenessFlutterApi`
// in Dart.
import 'package:pigeon/pigeon.dart';

/// Mirrors io.mosip.vivid.api.SessionState (enum names are passed as strings so Dart does not
/// need to track the Java enums; unknown values degrade gracefully in the overlay).
class SessionStateDto {
  SessionStateDto({
    required this.phase,
    required this.hint,
    this.challengeType,
    this.challengeIndex,
    this.challengeTotal,
    this.failureReason,
    this.failureSecuritySensitive,
    required this.attempt,
    required this.maxAttempts,
    required this.progress,
    required this.passiveScore,
    required this.remainingChallengeMillis,
    this.deviceHealth,
    this.deviceReason,
    this.faceBox,
  });

  /// IDLE, DEVICE_CONNECTING, FACE_ACQUIRING, PASSIVE_EVALUATING, ACTIVE_CHALLENGE, PASSED, FAILED, LOCKED, CANCELLED
  final String phase;
  /// SessionState.Hint name, e.g. MOVE_CLOSER, CHECKING_LIVENESS
  final String hint;
  /// ChallengeType name when phase == ACTIVE_CHALLENGE
  final String? challengeType;
  final int? challengeIndex;
  final int? challengeTotal;
  /// FailureReason name when phase == FAILED / LOCKED
  final String? failureReason;
  /// true when the failure must be shown generically (PAD, identity drift)
  final bool? failureSecuritySensitive;
  final int attempt;
  final int maxAttempts;
  final double progress;
  final double passiveScore;
  final int remainingChallengeMillis;
  /// DeviceHealth.State name (CONNECTING, CONNECTED, DEGRADED, RECONNECTING, DISCONNECTED, FAILED)
  final String? deviceHealth;
  final String? deviceReason;
  /// [x, y, w, h] normalised to the (unmirrored) analysis frame, for the oval guide feedback
  final List<double?>? faceBox;
}

class ChallengeOutcomeDto {
  ChallengeOutcomeDto({required this.type, required this.result, required this.latencyMillis, required this.durationMillis});
  final String type;
  /// PASSED, FAILED, TIMEOUT, ABORTED
  final String result;
  final int latencyMillis;
  final int durationMillis;
}

/// Mirrors io.mosip.vivid.api.LivenessResult minus the anchor embedding (stays native-side for
/// the rcapture identity-continuity check).
class LivenessResultDto {
  LivenessResultDto({
    required this.sessionId,
    required this.workflow,
    required this.decision,
    this.failureReason,
    required this.fusedLivenessScore,
    required this.signalScores,
    this.vetoSignal,
    this.attackClass,
    required this.challenges,
    required this.attemptsUsed,
    required this.passiveMillis,
    required this.totalMillis,
    required this.framesAnalyzed,
    required this.engineId,
    required this.modelBundleVersion,
    required this.policyVersion,
    required this.nonceHex,
    required this.evidenceHash,
  });
  final String sessionId;
  final String workflow;
  /// LIVE, NOT_LIVE, ATTACK, ERROR, CANCELLED
  final String decision;
  final String? failureReason;
  final double fusedLivenessScore;
  final Map<String?, double?> signalScores;
  final String? vetoSignal;
  final String? attackClass;
  final List<ChallengeOutcomeDto?> challenges;
  final int attemptsUsed;
  final int passiveMillis;
  final int totalMillis;
  final int framesAnalyzed;
  final String engineId;
  final String modelBundleVersion;
  final String policyVersion;
  final String nonceHex;
  final String evidenceHash;
}

/// What the Dart side needs to know about the effective policy of a workflow before starting a session.
class PolicySummaryDto {
  PolicySummaryDto({
    required this.enabled,
    required this.faceMatchEnabled,
    required this.faceMatchThreshold,
    required this.requireEnrolment,
    required this.policyVersion,
  });
  final bool enabled;
  final bool faceMatchEnabled;
  /// Cosine similarity threshold for the enrolled-face / capture-binding match.
  final double faceMatchThreshold;
  final bool requireEnrolment;
  final String policyVersion;
}

/// Result of matching a PASSED session's anchor embedding with a user's enrolled face.
class FaceMatchDto {
  FaceMatchDto({
    required this.status,
    required this.accepted,
    required this.similarity,
    required this.threshold,
    required this.referencesCompared,
    this.detail,
  });
  /// MATCHED, NOT_MATCHED, NO_ENROLLED_FACE, ENROLMENT_UNUSABLE, NO_ANCHOR, NO_FACE, DISABLED, UNAVAILABLE
  final String status;
  /// The policy decision: true lets the login / authentication continue.
  final bool accepted;
  /// Best cosine similarity, or -2 when nothing was compared.
  final double similarity;
  final double threshold;
  final int referencesCompared;
  final String? detail;
}

@HostApi()
abstract class LivenessHostApi {
  /// Starts a session for [workflow] (RESIDENT | OPERATOR | SUPERVISOR). [policyJson] is the
  /// effective policy (see policy module schema; "{}" means compiled-in defaults). [assisted]
  /// restricts challenges to the accessibility pool. Returns the session id; state arrives via
  /// [LivenessFlutterApi.onState].
  @async
  String startSession(String workflow, String policyJson, bool assisted);

  @async
  void cancel(String sessionId);

  /// Begins another attempt after FAILED. Returns false if policy forbids it.
  @async
  bool retry(String sessionId);

  /// Available once the session is FAILED or terminal.
  @async
  LivenessResultDto result(String sessionId);

  /// Flutter texture id carrying the CameraX preview for the active session, or -1 if none.
  @async
  int previewTextureId(String sessionId);

  /// Effective policy for [workflow] (host-app overrides merged over compiled-in defaults).
  @async
  PolicySummaryDto policySummary(String workflow);

  /// Compares the anchor embedding of PASSED session [sessionId] with the enrolled face of [userId]
  /// (operator login, supervisor authentication). Audited natively.
  @async
  FaceMatchDto matchEnrolledFace(String sessionId, String userId);
}

@FlutterApi()
abstract class LivenessFlutterApi {
  void onState(String sessionId, SessionStateDto state);
}
