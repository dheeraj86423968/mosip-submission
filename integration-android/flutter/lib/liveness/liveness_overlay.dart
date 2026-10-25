// LivenessOverlay: the face liveness screen shared (in intent) with the desktop JavaFX overlay.
//
// Layout (portrait, tablet or phone):
//   [ device chip ]                        [ offline chip ]
//              +-------------------------+
//              |   camera preview slot   |   <- Texture(previewTextureId) mirrored, or placeholder
//              |      ( oval guide )     |   <- _OvalGuidePainter, colour follows phase
//              +-------------------------+
//                   hint text (localised)
//           [ challenge card: icon + prompt + countdown ]
//                  o o o o  progress dots
//            attempt 2 of 3            [Cancel] [Retry]
//
// All user-facing strings go through LivenessMessages -> AppLocalizations keys.
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';

import 'liveness_controller.dart';
import 'liveness_messages.dart';

class LivenessOverlay extends StatelessWidget {
  const LivenessOverlay({
    super.key,
    required this.controller,
    this.onPassed,
    this.onGaveUp,
    this.onSupervisorOverride,
    this.mirrorPreview = true,
    this.previewPlaceholder,
  });

  final LivenessController controller;
  /// Called once when the session reaches PASSED (after result() is fetched).
  final void Function(LivenessResultDto result)? onPassed;
  /// Called when the user cancels or the session is LOCKED and no override is offered.
  final void Function(LivenessResultDto? result)? onGaveUp;
  /// When non-null, a LOCKED session shows a "Supervisor" button that invokes this.
  final VoidCallback? onSupervisorOverride;
  final bool mirrorPreview;
  /// Shown in the preview slot when no native texture is available (tests, desktop).
  final Widget? previewPlaceholder;

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider<LivenessController>.value(
      value: controller,
      child: _LivenessBody(
        onPassed: onPassed,
        onGaveUp: onGaveUp,
        onSupervisorOverride: onSupervisorOverride,
        mirrorPreview: mirrorPreview,
        previewPlaceholder: previewPlaceholder,
      ),
    );
  }
}

class _LivenessBody extends StatefulWidget {
  const _LivenessBody({
    required this.onPassed,
    required this.onGaveUp,
    required this.onSupervisorOverride,
    required this.mirrorPreview,
    required this.previewPlaceholder,
  });
  final void Function(LivenessResultDto result)? onPassed;
  final void Function(LivenessResultDto? result)? onGaveUp;
  final VoidCallback? onSupervisorOverride;
  final bool mirrorPreview;
  final Widget? previewPlaceholder;

  @override
  State<_LivenessBody> createState() => _LivenessBodyState();
}

class _LivenessBodyState extends State<_LivenessBody> {
  bool _completed = false;

  @override
  Widget build(BuildContext context) {
    final c = context.watch<LivenessController>();
    _maybeComplete(c);
    final s = c.state;
    final phase = c.phase;
    final colour = _phaseColour(phase, s);
    final theme = Theme.of(context);

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: SafeArea(
        child: LayoutBuilder(builder: (context, box) {
          final previewSize = (box.maxWidth * 0.86).clamp(240.0, 520.0);
          return Column(
            children: [
              _topBar(context, c),
              const SizedBox(height: 8),
              Expanded(
                child: Center(
                  child: SizedBox(
                    width: previewSize,
                    height: previewSize * 4 / 3,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        ClipRRect(
                          borderRadius: BorderRadius.circular(16),
                          child: _preview(context, c),
                        ),
                        CustomPaint(
                          painter: _OvalGuidePainter(
                            colour: colour,
                            progress: s?.progress ?? 0,
                            faceBox: s?.faceBox,
                            mirrored: widget.mirrorPreview,
                          ),
                        ),
                        if (c.isStarting || phase == 'DEVICE_CONNECTING')
                          const Center(child: CircularProgressIndicator()),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              _hintText(context, c, colour),
              const SizedBox(height: 12),
              if (phase == 'ACTIVE_CHALLENGE') _challengeCard(context, c, colour),
              if (c.isFailed || phase == 'LOCKED') _failureCard(context, c),
              const SizedBox(height: 12),
              _progressDots(context, c, colour),
              const SizedBox(height: 8),
              _footer(context, c),
              const SizedBox(height: 16),
            ],
          );
        }),
      ),
    );
  }

  // ---- pieces -----------------------------------------------------------------------------

  Widget _topBar(BuildContext context, LivenessController c) {
    final s = c.state;
    final health = s?.deviceHealth ?? 'CONNECTING';
    // Classic switch, not a switch expression: ARC's pubspec says sdk >=2.19.6 <3.0.0, so its
    // libraries are analysed at language version 2.19 even on Dart 3.
    final Color chipColour;
    switch (health) {
      case 'CONNECTED':
        chipColour = Colors.green;
        break;
      case 'DEGRADED':
      case 'RECONNECTING':
      case 'CONNECTING':
        chipColour = Colors.orange;
        break;
      default:
        chipColour = Colors.red;
    }
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          Chip(
            avatar: Icon(Icons.videocam, size: 16, color: chipColour),
            label: Text('${LivenessMessages.text(context, 'liveness.device_chip')} '
                '${_humanise(health)}'),
          ),
          const Spacer(),
          if (c.assisted)
            Chip(
              avatar: const Icon(Icons.accessibility_new, size: 16),
              label: Text(LivenessMessages.text(context, 'liveness.assisted_mode')),
            ),
          const SizedBox(width: 8),
          IconButton(
            tooltip: LivenessMessages.text(context, 'liveness.button.cancel'),
            icon: const Icon(Icons.close),
            onPressed: () => _cancel(c),
          ),
        ],
      ),
    );
  }

  Widget _preview(BuildContext context, LivenessController c) {
    if (c.previewTextureId >= 0) {
      final tex = Texture(textureId: c.previewTextureId);
      return widget.mirrorPreview
          ? Transform(alignment: Alignment.center, transform: Matrix4.diagonal3Values(-1, 1, 1), child: tex)
          : tex;
    }
    return widget.previewPlaceholder ??
        Container(
          color: Colors.black87,
          alignment: Alignment.center,
          child: const Icon(Icons.person_outline, size: 96, color: Colors.white24),
        );
  }

  Widget _hintText(BuildContext context, LivenessController c, Color colour) {
    final s = c.state;
    final key = s == null ? 'liveness.phase.idle' : LivenessMessages.hintKey(s.hint);
    final text = s != null && s.hint == 'NONE'
        ? LivenessMessages.text(context, LivenessMessages.phaseKey(s.phase))
        : LivenessMessages.text(context, key);
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 200),
      child: Text(
        text,
        key: ValueKey(text),
        textAlign: TextAlign.center,
        style: Theme.of(context).textTheme.titleMedium?.copyWith(color: colour, fontWeight: FontWeight.w600),
      ),
    );
  }

  Widget _challengeCard(BuildContext context, LivenessController c, Color colour) {
    final s = c.state!;
    final type = s.challengeType ?? 'HOLD_STILL';
    final total = s.challengeTotal ?? 1;
    final index = (s.challengeIndex ?? 0) + 1;
    final remaining = (c.remainingChallengeMillis / 1000).ceil();
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 24),
      elevation: 2,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(_challengeIcon(type), size: 40, color: colour),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    LivenessMessages.text(context, LivenessMessages.challengeKey(type)),
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 4),
                  Text('$index / $total', style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
            ),
            SizedBox(
              width: 44,
              height: 44,
              child: Stack(
                alignment: Alignment.center,
                children: [
                  CircularProgressIndicator(
                    value: s.remainingChallengeMillis == 0 ? 0 : c.remainingChallengeMillis / s.remainingChallengeMillis,
                    color: colour,
                  ),
                  Text('$remaining'),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _failureCard(BuildContext context, LivenessController c) {
    final s = c.state!;
    final key = s.phase == 'LOCKED'
        ? 'liveness.failure.locked'
        : LivenessMessages.failureKey(s.failureReason, securitySensitive: s.failureSecuritySensitive ?? false);
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 24),
      color: Theme.of(context).colorScheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
            const SizedBox(width: 12),
            Expanded(child: Text(LivenessMessages.text(context, key))),
          ],
        ),
      ),
    );
  }

  Widget _progressDots(BuildContext context, LivenessController c, Color colour) {
    const steps = ['DEVICE_CONNECTING', 'FACE_ACQUIRING', 'PASSIVE_EVALUATING', 'ACTIVE_CHALLENGE', 'PASSED'];
    final current = steps.indexOf(c.phase);
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        for (var i = 0; i < steps.length; i++)
          AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            margin: const EdgeInsets.symmetric(horizontal: 4),
            width: i == current ? 14 : 10,
            height: i == current ? 14 : 10,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: i <= current ? colour : Theme.of(context).disabledColor.withOpacity(0.3),
            ),
          ),
      ],
    );
  }

  Widget _footer(BuildContext context, LivenessController c) {
    final s = c.state;
    final attempt = s?.attempt ?? 0;
    final max = s?.maxAttempts ?? 0;
    final attemptText = LivenessMessages.text(context, 'liveness.attempt_of')
        .replaceAll('{attempt}', '$attempt')
        .replaceAll('{max}', '$max');
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Row(
        children: [
          Text(attemptText, style: Theme.of(context).textTheme.bodyMedium),
          const Spacer(),
          if (c.phase == 'LOCKED' && widget.onSupervisorOverride != null)
            FilledButton.tonalIcon(
              icon: const Icon(Icons.supervisor_account),
              label: Text(LivenessMessages.text(context, 'liveness.button.supervisor')),
              onPressed: widget.onSupervisorOverride,
            ),
          if (c.canRetry) ...[
            const SizedBox(width: 8),
            FilledButton.icon(
              icon: const Icon(Icons.refresh),
              label: Text(LivenessMessages.text(context, 'liveness.button.retry')),
              onPressed: () => c.retry(),
            ),
          ],
          if (c.phase == 'LOCKED' || (c.isFailed && !c.canRetry)) ...[
            const SizedBox(width: 8),
            OutlinedButton(
              onPressed: () => _giveUp(c),
              child: Text(LivenessMessages.text(context, 'liveness.button.cancel')),
            ),
          ],
        ],
      ),
    );
  }

  // ---- behaviour --------------------------------------------------------------------------

  Future<void> _maybeComplete(LivenessController c) async {
    if (_completed || !c.passed) return;
    _completed = true;
    final r = await c.fetchResult();
    if (r != null && mounted) widget.onPassed?.call(r);
  }

  Future<void> _cancel(LivenessController c) async {
    await c.cancel();
    await _giveUp(c);
  }

  Future<void> _giveUp(LivenessController c) async {
    if (_completed) return;
    _completed = true;
    final r = await c.fetchResult();
    if (mounted) widget.onGaveUp?.call(r);
  }

  Color _phaseColour(String phase, SessionStateDto? s) {
    switch (phase) {
      case 'PASSED':
        return Colors.green.shade600;
      case 'FAILED':
      case 'LOCKED':
        return Colors.red.shade600;
      case 'ACTIVE_CHALLENGE':
        return Colors.blue.shade600;
      case 'PASSIVE_EVALUATING':
        return Colors.teal.shade600;
      case 'FACE_ACQUIRING':
        return (s?.hint == 'NONE' || s?.hint == 'HOLD_STILL') ? Colors.teal.shade400 : Colors.orange.shade600;
      default:
        return Colors.grey.shade500;
    }
  }

  IconData _challengeIcon(String type) {
    switch (type) {
      case 'BLINK':
      case 'BLINK_TWICE':
        return Icons.remove_red_eye_outlined;
      case 'SMILE':
        return Icons.sentiment_satisfied_alt;
      case 'OPEN_MOUTH':
        return Icons.record_voice_over;
      case 'RAISE_EYEBROWS':
        return Icons.face_retouching_natural;
      case 'TURN_LEFT':
        return Icons.arrow_back;
      case 'TURN_RIGHT':
        return Icons.arrow_forward;
      case 'LOOK_UP':
        return Icons.arrow_upward;
      case 'LOOK_DOWN':
        return Icons.arrow_downward;
      case 'NOD':
        return Icons.swap_vert;
      default:
        return Icons.pause_circle_outline;
    }
  }

  String _humanise(String enumName) => enumName.toLowerCase().replaceAll('_', ' ');
}

/// Oval face guide with a progress arc and a faint face-box echo for positioning feedback.
class _OvalGuidePainter extends CustomPainter {
  _OvalGuidePainter({required this.colour, required this.progress, this.faceBox, required this.mirrored});
  final Color colour;
  final double progress;
  final List<double?>? faceBox;
  final bool mirrored;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Rect.fromCenter(
      center: Offset(size.width / 2, size.height * 0.46),
      width: size.width * 0.62,
      height: size.height * 0.60,
    );
    // Dim everything outside the oval.
    final dim = Path()
      ..addRect(Offset.zero & size)
      ..addOval(rect)
      ..fillType = PathFillType.evenOdd;
    canvas.drawPath(dim, Paint()..color = Colors.black.withOpacity(0.45));
    // Guide ring.
    canvas.drawOval(rect, Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..color = colour.withOpacity(0.9));
    // Progress arc (clockwise from the top).
    if (progress > 0) {
      canvas.drawArc(rect.inflate(6), -1.5708, 6.2832 * progress.clamp(0.0, 1.0), false, Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 5
        ..strokeCap = StrokeCap.round
        ..color = colour);
    }
    // Face box echo (normalised to the unmirrored analysis frame).
    final fb = faceBox;
    if (fb != null && fb.length == 4 && fb.every((v) => v != null)) {
      var x = fb[0]!;
      final y = fb[1]!, w = fb[2]!, h = fb[3]!;
      if (mirrored) x = 1 - x - w;
      final r = Rect.fromLTWH(x * size.width, y * size.height, w * size.width, h * size.height);
      canvas.drawRRect(RRect.fromRectAndRadius(r, const Radius.circular(8)), Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5
        ..color = colour.withOpacity(0.5));
    }
  }

  @override
  bool shouldRepaint(_OvalGuidePainter old) =>
      old.colour != colour || old.progress != progress || old.faceBox != faceBox || old.mirrored != mirrored;
}
