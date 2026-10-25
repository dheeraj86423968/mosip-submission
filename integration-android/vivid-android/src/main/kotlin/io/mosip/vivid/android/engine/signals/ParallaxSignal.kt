package io.mosip.vivid.android.engine.signals

import io.mosip.vivid.api.ChallengeType
import io.mosip.vivid.api.SignalScore
import io.mosip.vivid.api.Signals
import kotlin.math.abs
import kotlin.math.exp

/**
 * S5: 3D-structure / parallax check during head-motion challenges.
 *
 * While a depth-revealing challenge (TURN_*, LOOK_*, NOD) is active we keep the landmark set from
 * the first near-frontal frame as the reference. For each later frame whose pose differs enough
 * (|delta yaw| or |delta pitch| >= [minAngle] degrees) we fit a planar homography from the
 * reference to the current landmarks and measure the re-projection residual, normalised by the
 * face width. A flat object (print, phone, tablet) is explained by a homography to within
 * landmark noise (~0.5% of face width); a real face, whose nose/cheeks/chin sit at different
 * depths, leaves a residual several times larger (typically 2-4%).
 *
 * Uses a 24-point subset of MediaPipe's 478 landmarks (eye corners, brows, nose bridge/tip/wings,
 * mouth corners, chin, cheeks, forehead) chosen for being rigid (not expression-driven).
 * Indices are MediaPipe Face Mesh canonical indices; [SignalInput.face] carries all 478 as
 * normalised x,y pairs.
 */
class ParallaxSignal(private val minAngle: Double = 12.0) : LivenessSignal {
    override val id: String = Signals.PARALLAX

    companion object {
        /** Rigid landmark subset (MediaPipe canonical face-mesh indices). */
        val RIGID = intArrayOf(
            1, 4, 5, 6, 168, 197, 195,   // nose tip, bridge, dorsum
            33, 133, 362, 263,           // eye outer/inner corners
            70, 105, 107, 336, 334, 300, // brows
            48, 278, 98, 327,            // nose wings / alar base
            152, 10,                      // chin, forehead
            234                           // left cheek contour (plus right below)
        ) + intArrayOf(454)               // right cheek contour
    }

    private var reference: DoubleArray? = null
    private var refYaw = 0.0; private var refPitch = 0.0
    private val residuals = ArrayList<Double>()
    private var activeType: ChallengeType? = null
    private var last: SignalScore? = null

    override fun score(input: SignalInput): SignalScore? {
        val ch = input.activeChallenge
        val type = ch?.type()
        if (type == null || !type.revealsDepth()) {
            // Outside a depth-revealing challenge the signal only repeats what it last concluded.
            return last ?: SignalScore.of(id, 0.5, 0.0)
        }
        if (type != activeType) { activeType = type; reference = null; residuals.clear() }
        val lm = input.face.landmarks()
        if (lm.size < 2 * 468) return last ?: SignalScore.of(id, 0.5, 0.0)
        val pts = select(lm, input.frame.width(), input.frame.height())
        val yaw = input.face.yaw(); val pitch = input.face.pitch()
        val ref = reference
        if (ref == null) {
            if (abs(yaw) < 8 && abs(pitch) < 8) { reference = pts; refYaw = yaw; refPitch = pitch }
            return SignalScore.of(id, 0.5, 0.0)
        }
        val delta = maxOf(abs(yaw - refYaw), abs(pitch - refPitch))
        if (delta < minAngle) return last ?: SignalScore.of(id, 0.5, 0.0)
        val h = Homography.estimate(ref, pts) ?: return last ?: SignalScore.of(id, 0.5, 0.0)
        val faceWidthPx = input.face.boxW() * input.frame.width()
        val res = Homography.residual(h, ref, pts) / faceWidthPx.coerceAtLeast(1.0)
        residuals += res
        val median = residuals.sorted()[residuals.size / 2]
        // Logistic: residual 0.6% -> attack 0.8, 1.2% -> 0.5, 2.5% -> 0.1
        val p = 1.0 / (1.0 + exp((median - 0.012) / 0.004))
        val conf = (0.4 + 0.1 * residuals.size).coerceAtMost(0.9) * (delta / 25.0).coerceIn(0.5, 1.0)
        val s = SignalScore(id, p, conf, residuals.size >= 5 && median < 0.004, if (p > 0.5) Signals.ATTACK_PRINT else null)
        last = s
        return s
    }

    private fun select(lm: DoubleArray, w: Int, h: Int): DoubleArray {
        val out = DoubleArray(RIGID.size * 2)
        for ((i, idx) in RIGID.withIndex()) { out[2 * i] = lm[2 * idx] * w; out[2 * i + 1] = lm[2 * idx + 1] * h }
        return out
    }

    override fun reset() { reference = null; residuals.clear(); activeType = null; last = null }
}
