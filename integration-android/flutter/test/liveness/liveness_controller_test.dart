// Runs inside a package named `registration_client` (ARC, or the throwaway package described in
// docs/testing/android-compile.md) after `dart run pigeon` has generated lib/pigeon/liveness_pigeon.dart.
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:registration_client/liveness/liveness_controller.dart';
import 'package:registration_client/liveness/liveness_messages.dart';
import 'package:registration_client/liveness/liveness_overlay.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';

SessionStateDto _state(String phase, String hint,
        {String? challenge, int attempt = 1, int max = 3, int remaining = 0, String? failure}) =>
    SessionStateDto(
      phase: phase,
      hint: hint,
      challengeType: challenge,
      challengeIndex: challenge == null ? null : 1,
      challengeTotal: challenge == null ? null : 2,
      failureReason: failure,
      attempt: attempt,
      maxAttempts: max,
      progress: 0.5,
      passiveScore: 0.6,
      remainingChallengeMillis: remaining,
      deviceHealth: 'CONNECTED',
    );

/// Simulates the native side pushing LivenessFlutterApi.onState over the real Pigeon channel.
Future<void> _push(String id, SessionStateDto s) {
  final completer = Completer<void>();
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
    'dev.flutter.pigeon.registration_client.LivenessFlutterApi.onState',
    LivenessFlutterApi.codec.encodeMessage(<Object?>[id, s]),
    (_) => completer.complete(),
  );
  return completer.future;
}

class _FakeHost implements LivenessHostApi {
  _FakeHost({this.earlyState});
  final SessionStateDto? earlyState;
  final calls = <String>[];
  bool allowRetry = true;

  @override
  Future<String> startSession(String workflow, String policyJson, bool assisted) async {
    calls.add('start:$workflow:$assisted');
    // Native publishes the first state before replying to startSession.
    if (earlyState != null) await _push('s1', earlyState!);
    return 's1';
  }

  @override
  Future<void> cancel(String sessionId) async => calls.add('cancel:$sessionId');

  @override
  Future<bool> retry(String sessionId) async {
    calls.add('retry:$sessionId');
    return allowRetry;
  }

  @override
  Future<LivenessResultDto> result(String sessionId) async => throw UnimplementedError();

  @override
  Future<int> previewTextureId(String sessionId) async => 7;

  FaceMatchDto match = FaceMatchDto(
      status: 'MATCHED', accepted: true, similarity: 0.71, threshold: 0.5, referencesCompared: 1);

  @override
  Future<PolicySummaryDto> policySummary(String workflow) async {
    calls.add('policy:$workflow');
    return PolicySummaryDto(
        enabled: workflow != 'RESIDENT',
        faceMatchEnabled: true,
        faceMatchThreshold: 0.5,
        requireEnrolment: false,
        policyVersion: 'test');
  }

  @override
  Future<FaceMatchDto> matchEnrolledFace(String sessionId, String userId) async {
    calls.add('match:$sessionId:$userId');
    return match;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('policy summary and enrolled-face match go to the host for this session', () async {
    final host = _FakeHost();
    final c = LivenessController(api: host);
    expect(await c.matchEnrolledFace('op1'), isNull, reason: 'no session yet');
    final p = await c.policySummary(LivenessWorkflow.resident);
    expect(p!.enabled, isFalse);
    await c.start(LivenessWorkflow.operator);
    final m = await c.matchEnrolledFace('op1');
    expect(m!.accepted, isTrue);
    expect(m.status, 'MATCHED');
    expect(host.calls, ['policy:RESIDENT', 'start:OPERATOR:false', 'match:s1:op1']);
    c.dispose();
  });

  test('state pushed before startSession returns is not lost', () async {
    final host = _FakeHost(earlyState: _state('FACE_ACQUIRING', 'CENTER_FACE'));
    final c = LivenessController(api: host);
    final id = await c.start(LivenessWorkflow.operator);
    expect(id, 's1');
    expect(host.calls, ['start:OPERATOR:false']);
    expect(c.previewTextureId, 7);
    expect(c.phase, 'FACE_ACQUIRING');
    expect(c.state!.hint, 'CENTER_FACE');
    c.dispose();
  });

  test('retry is only offered while attempts remain; dispose cancels a live session', () async {
    final host = _FakeHost();
    final c = LivenessController(api: host);
    await c.start(LivenessWorkflow.resident, assisted: true);
    await _push('s1', _state('FAILED', 'RETRY_AVAILABLE', attempt: 1, max: 3, failure: 'CHALLENGE_TIMEOUT'));
    expect(c.isFailed, isTrue);
    expect(c.canRetry, isTrue);
    expect(await c.retry(), isTrue);
    await _push('s1', _state('FAILED', 'MAX_RETRIES_REACHED', attempt: 3, max: 3));
    expect(c.canRetry, isFalse);
    expect(await c.retry(), isFalse);
    c.dispose();
    expect(host.calls, ['start:RESIDENT:true', 'retry:s1', 'cancel:s1']);
  });

  test('every Hint name maps to a dotted key with an ARB entry', () {
    const hints = ['NONE', 'MOVE_CLOSER', 'MOVE_BACK', 'CENTER_FACE', 'LOOK_AT_CAMERA', 'IMPROVE_LIGHTING',
      'HOLD_STILL', 'ONLY_ONE_FACE', 'CHECKING_LIVENESS', 'PLEASE_CONTINUE', 'ACTION_DETECTED', 'CAPTURED_OK',
      'TRY_AGAIN', 'RETRY_AVAILABLE', 'MAX_RETRIES_REACHED', 'DEVICE_RECONNECTING', 'DEVICE_DISCONNECTED'];
    for (final h in hints) {
      expect(LivenessMessages.hintKey(h), startsWith('liveness.hint.'));
    }
    expect(LivenessMessages.arbKey('liveness.hint.checking'), 'liveness_hint_checking');
  });

  testWidgets('overlay renders localized hint and challenge text', (tester) async {
    final c = LivenessController(api: _FakeHost());
    await c.start(LivenessWorkflow.resident);
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(body: LivenessOverlay(controller: c)),
    ));
    await _push('s1', _state('PASSIVE_EVALUATING', 'CHECKING_LIVENESS'));
    await tester.pump();
    expect(find.text('Checking liveness...'), findsWidgets);
    await _push('s1', _state('ACTIVE_CHALLENGE', 'PLEASE_CONTINUE', challenge: 'TURN_LEFT', remaining: 3000));
    await tester.pump(const Duration(milliseconds: 250));
    expect(find.text('Turn your head to the left'), findsWidgets);
    expect(c.remainingChallengeMillis, lessThan(3000));
    // Leave the session terminal so dispose does not try to cancel through the fake.
    await _push('s1', _state('PASSED', 'CAPTURED_OK'));
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });
}
