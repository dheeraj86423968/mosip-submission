// ChangeNotifier that drives LivenessOverlay from Pigeon events.
//
// Lifecycle: create -> start(workflow) -> listen (state) -> PASSED / FAILED / LOCKED
//            -> retry() or cancel() -> result() -> dispose().
import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';

/// Where the session is used; mirrors io.mosip.vivid.api.Workflow.
enum LivenessWorkflow { resident, operator, supervisor }

extension LivenessWorkflowName on LivenessWorkflow {
  String get wireName => name.toUpperCase();
}

/// Receives native -> Dart state pushes and fans them out to the controller for that session.
class _LivenessEvents extends LivenessFlutterApi {
  static final _LivenessEvents instance = _LivenessEvents._();
  _LivenessEvents._() {
    LivenessFlutterApi.setup(this);
  }
  final Map<String, void Function(SessionStateDto)> _listeners = {};
  // The native side publishes the first state before the startSession() reply is delivered, so a
  // state can arrive for a session nobody listens to yet. Keep the latest one and replay it.
  final Map<String, SessionStateDto> _pending = {};

  @override
  void onState(String sessionId, SessionStateDto state) {
    final l = _listeners[sessionId];
    if (l != null) {
      l(state);
    } else {
      if (_pending.length > 8) _pending.remove(_pending.keys.first);
      _pending[sessionId] = state;
    }
  }

  void listen(String sessionId, void Function(SessionStateDto) listener) {
    _listeners[sessionId] = listener;
    final early = _pending.remove(sessionId);
    if (early != null) listener(early);
  }

  void unlisten(String sessionId) {
    _listeners.remove(sessionId);
    _pending.remove(sessionId);
  }
}

class LivenessController extends ChangeNotifier {
  LivenessController({LivenessHostApi? api}) : _api = api ?? LivenessHostApi() {
    _LivenessEvents.instance; // ensure the FlutterApi is registered once
  }

  final LivenessHostApi _api;

  String? _sessionId;
  SessionStateDto? _state;
  LivenessResultDto? _result;
  int _textureId = -1;
  bool _starting = false;
  String? _error;
  bool _assisted = false;
  Timer? _countdown;
  int _remainingMillis = 0;

  String? get sessionId => _sessionId;
  SessionStateDto? get state => _state;
  LivenessResultDto? get result => _result;
  int get previewTextureId => _textureId;
  bool get isStarting => _starting;
  String? get error => _error;
  bool get assisted => _assisted;

  String get phase => _state?.phase ?? 'IDLE';
  bool get isTerminal => phase == 'PASSED' || phase == 'LOCKED' || phase == 'CANCELLED';
  bool get isFailed => phase == 'FAILED';
  bool get canRetry => isFailed && (_state?.attempt ?? 0) < (_state?.maxAttempts ?? 0);
  bool get passed => phase == 'PASSED';
  /// Milliseconds left on the active challenge, ticked locally between native updates.
  int get remainingChallengeMillis => _remainingMillis;

  /// Starts a session. [policyJson] comes from GlobalConfigSettingsApi.getLivenessPolicy(workflow)
  /// (or '{}' for compiled-in defaults). Returns the session id, or null on failure.
  Future<String?> start(LivenessWorkflow workflow,
      {String policyJson = '{}', bool assisted = false}) async {
    if (_starting || (_sessionId != null && !isTerminal)) return _sessionId;
    _starting = true;
    _error = null;
    _result = null;
    _assisted = assisted;
    notifyListeners();
    try {
      final id = await _api.startSession(workflow.wireName, policyJson, assisted);
      _sessionId = id;
      _LivenessEvents.instance.listen(id, _onState);
      _textureId = await _api.previewTextureId(id);
    } on PlatformException catch (e) {
      _error = e.message ?? e.code;
      debugPrint('LivenessHostApi.startSession failed: $e');
    } catch (e) {
      _error = e.toString();
      debugPrint('LivenessHostApi.startSession failed: $e');
    } finally {
      _starting = false;
      notifyListeners();
    }
    return _sessionId;
  }

  Future<bool> retry() async {
    final id = _sessionId;
    if (id == null || !canRetry) return false;
    try {
      final ok = await _api.retry(id);
      if (ok) {
        _result = null;
        notifyListeners();
      }
      return ok;
    } catch (e) {
      debugPrint('LivenessHostApi.retry failed: $e');
      return false;
    }
  }

  Future<void> cancel() async {
    final id = _sessionId;
    if (id == null) return;
    try {
      await _api.cancel(id);
    } catch (e) {
      debugPrint('LivenessHostApi.cancel failed: $e');
    }
  }

  /// Fetches the final result (available once FAILED or terminal). Cached.
  Future<LivenessResultDto?> fetchResult() async {
    final id = _sessionId;
    if (id == null) return null;
    if (_result != null) return _result;
    try {
      _result = await _api.result(id);
      notifyListeners();
    } catch (e) {
      debugPrint('LivenessHostApi.result failed: $e');
    }
    return _result;
  }

  /// Effective policy for [workflow] (host configuration merged over compiled-in defaults), or null
  /// when the native side is unavailable.
  Future<PolicySummaryDto?> policySummary(LivenessWorkflow workflow) async {
    try {
      return await _api.policySummary(workflow.wireName);
    } catch (e) {
      debugPrint('LivenessHostApi.policySummary failed: $e');
      return null;
    }
  }

  /// Matches this (PASSED) session's anchor embedding against [userId]'s enrolled face. Returns null
  /// on a channel error; callers must treat null as "not accepted".
  Future<FaceMatchDto?> matchEnrolledFace(String userId) async {
    final id = _sessionId;
    if (id == null) return null;
    try {
      return await _api.matchEnrolledFace(id, userId);
    } catch (e) {
      debugPrint('LivenessHostApi.matchEnrolledFace failed: $e');
      return null;
    }
  }

  void _onState(SessionStateDto s) {
    _state = s;
    _remainingMillis = s.remainingChallengeMillis;
    _countdown?.cancel();
    if (s.phase == 'ACTIVE_CHALLENGE' && _remainingMillis > 0) {
      _countdown = Timer.periodic(const Duration(milliseconds: 100), (t) {
        _remainingMillis = (_remainingMillis - 100).clamp(0, 1 << 30);
        if (_remainingMillis == 0) t.cancel();
        notifyListeners();
      });
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _countdown?.cancel();
    final id = _sessionId;
    if (id != null) {
      _LivenessEvents.instance.unlisten(id);
      if (!isTerminal) {
        // Fire and forget; the native side tears the camera down.
        _api.cancel(id).catchError((_) {});
      }
    }
    super.dispose();
  }
}
