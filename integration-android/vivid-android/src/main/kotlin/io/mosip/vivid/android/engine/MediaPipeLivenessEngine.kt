package io.mosip.vivid.android.engine

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import io.mosip.vivid.android.engine.signals.ColorStatsSignal
import io.mosip.vivid.android.engine.signals.LivenessSignal
import io.mosip.vivid.android.engine.signals.MoireSignal
import io.mosip.vivid.android.engine.signals.ParallaxSignal
import io.mosip.vivid.android.engine.signals.RppgSignal
import io.mosip.vivid.android.engine.signals.SignalInput
import io.mosip.vivid.android.engine.signals.TfliteEmbeddingSignal
import io.mosip.vivid.android.engine.signals.TfliteTextureSignal
import io.mosip.vivid.android.perf.ThermalGovernor
import io.mosip.vivid.api.Challenge
import io.mosip.vivid.api.ChallengeType
import io.mosip.vivid.api.EngineInfo
import io.mosip.vivid.api.FaceObservation
import io.mosip.vivid.api.FaceQuality
import io.mosip.vivid.api.FrameAnalysis
import io.mosip.vivid.api.FrameAnalyzer
import io.mosip.vivid.api.LivenessEngine
import io.mosip.vivid.api.SignalScore
import io.mosip.vivid.api.Signals
import io.mosip.vivid.device.FaceFrame
import java.nio.ByteBuffer
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * [LivenessEngine] for Android built on MediaPipe Tasks Vision `FaceLandmarker` (478 landmarks,
 * 52 blendshapes, facial transformation matrix) plus TFLite signals (PLAN 8.1).
 *
 * The engine owns the models (loaded once, shared); each [newAnalyzer] is a cheap per-session
 * object with its own temporal state. The `FaceLandmarker` runs in VIDEO mode, which is not
 * re-entrant, so analyzers serialise on [landmarkerLock]; in practice one session runs at a time.
 *
 * Face geometry mapping to the model-agnostic [FaceObservation]:
 *  - yaw/pitch/roll from the 4x4 facial transformation matrix (column-major), decomposed as
 *    R = Ry(yaw) Rx(pitch) Rz(roll); yaw sign flipped so +yaw = subject turned to their left.
 *    NOTE: verify sign on a real device with the front camera (frames are unmirrored).
 *  - eye aspect from `eyeBlinkLeft/Right` (EAR-like: 0.05 closed .. 0.33 open)
 *  - smile from mean(`mouthSmileLeft`, `mouthSmileRight`)
 *  - mouthAspect from `jawOpen`
 *  - browRaise from `browInnerUp` (max with outer brows)
 *  - gaze from `eyeLookIn/Out/Up/Down` blendshapes, +x = subject looking to their left
 */
class MediaPipeLivenessEngine(
    private val context: Context,
    private val assets: ModelAssets = ModelAssets.load(context),
    private val thermalGovernor: ThermalGovernor? = null,
    private val preferGpu: Boolean = true
) : LivenessEngine {

    companion object {
        const val ENGINE_ID = "mediapipe-android"
        const val ENGINE_VERSION = "0.1.0"
        private const val TAG = "MediaPipeLivenessEngine"
        private const val MINIFASNET_SCALE = 2.7
        private val SUPPORTED = EnumSet.of(
            ChallengeType.BLINK, ChallengeType.BLINK_TWICE, ChallengeType.SMILE, ChallengeType.OPEN_MOUTH,
            ChallengeType.RAISE_EYEBROWS, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT, ChallengeType.LOOK_UP,
            ChallengeType.LOOK_DOWN, ChallengeType.NOD, ChallengeType.HOLD_STILL)
    }

    private val landmarkerLock = Any()
    private val landmarker: FaceLandmarker = buildLandmarker()
    private val texture: TfliteTextureSignal? = runCatching { TfliteTextureSignal(assets, preferGpu) }
        .onFailure { Log.e(TAG, "texture model unavailable; S1 disabled", it) }.getOrNull()
    private val embedding: TfliteEmbeddingSignal? = runCatching { TfliteEmbeddingSignal(assets) }
        .onFailure { Log.e(TAG, "embedding model unavailable; identity continuity disabled", it) }.getOrNull()
    private var rgbaScratch: ByteBuffer? = null

    private fun buildLandmarker(): FaceLandmarker {
        val base = BaseOptions.builder()
        val path = assets.assetPath(ModelAssets.KEY_FACE_LANDMARKER)
        if (path != null) base.setModelAssetPath(path) else base.setModelAssetBuffer(assets.bytes(ModelAssets.KEY_FACE_LANDMARKER))
        base.setDelegate(if (preferGpu) Delegate.GPU else Delegate.CPU)
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base.build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(2) // 2 so MULTIPLE_FACES can be detected; cheap because detection is shared
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return try {
            FaceLandmarker.createFromOptions(context, opts)
        } catch (e: Exception) {
            if (!preferGpu) throw e
            Log.w(TAG, "FaceLandmarker GPU init failed, retrying on CPU: ${e.message}")
            val cpuBase = BaseOptions.builder().setDelegate(Delegate.CPU)
            if (path != null) cpuBase.setModelAssetPath(path) else cpuBase.setModelAssetBuffer(assets.bytes(ModelAssets.KEY_FACE_LANDMARKER))
            FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(cpuBase.build()).setRunningMode(RunningMode.VIDEO).setNumFaces(2)
                .setOutputFaceBlendshapes(true).setOutputFacialTransformationMatrixes(true).build())
        }
    }

    override fun info(): EngineInfo = EngineInfo(
        ENGINE_ID, ENGINE_VERSION, assets.bundleId, assets.bundleVersion, SUPPORTED,
        setOf(Signals.CNN_TEXTURE, Signals.MOIRE, Signals.COLOR_STATS, Signals.RPPG, Signals.PARALLAX, Signals.QUALITY),
        embedding != null)

    override fun newAnalyzer(): FrameAnalyzer = Analyzer()

    override fun selfTest(): LivenessEngine.SelfTestResult {
        // The packaged self-test corpus (known live/attack crops) ships with the model bundle;
        // until it does, report skipped rather than pretending.
        return if (assets.has("selftest")) LivenessEngine.SelfTestResult.skipped("self-test corpus present but runner not implemented on Android yet")
        else LivenessEngine.SelfTestResult.skipped("no self-test corpus in bundle ${assets.bundleId}")
    }

    override fun close() {
        synchronized(landmarkerLock) { landmarker.close() }
        texture?.close()
        embedding?.close()
    }

    /** Per-session analyzer. Not thread-safe (one feeding thread), as the API requires. */
    private inner class Analyzer : FrameAnalyzer {
        private val signals: List<LivenessSignal> = listOfNotNull(texture, MoireSignal(), ColorStatsSignal(), RppgSignal(), ParallaxSignal())
        private var challenge: Challenge? = null
        private var frameIndex = 0L
        private var lastTimestampMs = -1L

        override fun analyze(frame: FaceFrame): FrameAnalysis {
            val t0 = System.nanoTime()
            frameIndex++
            val level = thermalGovernor?.level ?: ThermalGovernor.Level.NORMAL
            texture?.everyN = level.textureEveryN

            val result: FaceLandmarkerResult? = synchronized(landmarkerLock) {
                try {
                    val rgba = FrameImageOps.toRgba(frame, rgbaScratch).also { rgbaScratch = it }
                    val image: MPImage = ByteBufferImageBuilder(rgba, frame.width(), frame.height(), MPImage.IMAGE_FORMAT_RGBA).build()
                    // VIDEO mode needs monotonically increasing timestamps.
                    var ts = frame.timestampNanos() / 1_000_000
                    if (ts <= lastTimestampMs) ts = lastTimestampMs + 1
                    lastTimestampMs = ts
                    landmarker.detectForVideo(image, ts)
                } catch (e: Exception) {
                    Log.e(TAG, "landmarker failed", e); null
                }
            }

            val faces = result?.faceLandmarks()?.size ?: 0
            if (faces != 1) {
                val q = if (faces == 0) FaceQuality.noFace() else FaceQuality(faces, 0.0, 0.0, 0.0, 0.0, 0.0)
                return FrameAnalysis(frame.sequence(), frame.timestampNanos(), q, null, listOf(SignalScore.of(Signals.QUALITY, 0.0, 1.0)), null, System.nanoTime() - t0)
            }
            val face = toObservation(result!!)
            val quality = computeQuality(frame, face)

            // Crops: tight square (112) for colour/moiré/rPPG/embedding, loose 2.7x (80) for MiniFASNet.
            val w = frame.width(); val h = frame.height()
            val cx = face.centerX() * w; val cy = face.centerY() * h
            val side = maxOf(face.boxW() * w, face.boxH() * h)
            val tight = FrameImageOps.cropRgb(frame, (cx - side / 2).toInt(), (cy - side / 2).toInt(), (cx + side / 2).toInt(), (cy + side / 2).toInt(), 112, 112)
            val ls = side * MINIFASNET_SCALE
            val loose = FrameImageOps.cropRgb(frame, (cx - ls / 2).toInt(), (cy - ls / 2).toInt(), (cx + ls / 2).toInt(), (cy + ls / 2).toInt(), TfliteTextureSignal.INPUT, TfliteTextureSignal.INPUT)
            val input = SignalInput(frame, face, tight, loose, challenge, frameIndex)

            val scores = ArrayList<SignalScore>(signals.size + 1)
            scores += SignalScore.of(Signals.QUALITY, 1.0 - quality.overall(), 1.0)
            for (s in signals) {
                if (s.id == Signals.RPPG && !(thermalGovernor?.rppgEnabled ?: true)) continue
                try { s.score(input)?.let { scores += it } } catch (e: Exception) { Log.w(TAG, "signal ${s.id} failed", e) }
            }
            val emb = if (quality.overall() >= 0.4) embedding?.embed(tight) else null
            return FrameAnalysis(frame.sequence(), frame.timestampNanos(), quality, face, scores, emb, System.nanoTime() - t0)
        }

        override fun onChallenge(challenge: Challenge?) { this.challenge = challenge }

        override fun reset() {
            for (s in signals) s.reset()
            embedding?.reset()
            challenge = null
            frameIndex = 0
        }

        override fun close() { /* models are shared; nothing per-analyzer to release */ }

        private fun computeQuality(frame: FaceFrame, face: FaceObservation): FaceQuality {
            val w = frame.width(); val h = frame.height()
            val x0 = (face.boxX() * w).toInt(); val y0 = (face.boxY() * h).toInt()
            val x1 = ((face.boxX() + face.boxW()) * w).toInt(); val y1 = ((face.boxY() + face.boxH()) * h).toInt()
            val gray = FrameImageOps.toGray(FrameImageOps.cropRgb(frame, x0, y0, x1, y1, 96, 96))
            val sharp = FrameImageOps.laplacianSharpness(gray)
            val illum = FrameImageOps.illuminationQuality(gray)
            val pose = (1.0 - maxOf(abs(face.yaw()), abs(face.pitch())) / 45.0).coerceIn(0.0, 1.0)
            // MediaPipe gives no occlusion estimate; a low landmark presence score would show up as
            // no face. Report 1.0 and let the orchestrator's single-face/size gates do the work.
            val occlusion = 1.0
            val rel = face.relativeSize()
            val size = when {
                rel < 0.18 -> (rel / 0.18).coerceIn(0.0, 1.0)      // too far
                rel > 0.75 -> ((1.0 - rel) / 0.25).coerceIn(0.0, 1.0) // too close
                else -> 1.0
            }
            return FaceQuality(1, sharp, illum, pose, occlusion, size)
        }
    }

    // ---- MediaPipe result -> FaceObservation -----------------------------------------------

    internal fun toObservation(r: FaceLandmarkerResult): FaceObservation {
        val lm = r.faceLandmarks()[0]
        val pts = DoubleArray(lm.size * 2)
        var minX = 1.0; var minY = 1.0; var maxX = 0.0; var maxY = 0.0
        for ((i, p) in lm.withIndex()) {
            val x = p.x().toDouble(); val y = p.y().toDouble()
            pts[2 * i] = x; pts[2 * i + 1] = y
            if (x < minX) minX = x; if (y < minY) minY = y; if (x > maxX) maxX = x; if (y > maxY) maxY = y
        }
        val bs = HashMap<String, Double>(64)
        r.faceBlendshapes().ifPresent { all -> if (all.isNotEmpty()) for (c in all[0]) bs[c.categoryName()] = c.score().toDouble() }
        fun b(name: String) = bs[name] ?: 0.0

        var yaw = 0.0; var pitch = 0.0; var roll = 0.0
        r.facialTransformationMatrixes().ifPresent { ms ->
            if (ms.isNotEmpty()) { val e = eulerFromMatrix(ms[0]); yaw = e[0]; pitch = e[1]; roll = e[2] }
        }
        val ear = { blink: Double -> 0.05 + 0.28 * (1.0 - blink.coerceIn(0.0, 1.0)) }
        val gazeX = 0.5 * ((b("eyeLookOutLeft") - b("eyeLookInLeft")) + (b("eyeLookInRight") - b("eyeLookOutRight")))
        val gazeY = 0.5 * ((b("eyeLookUpLeft") - b("eyeLookDownLeft")) + (b("eyeLookUpRight") - b("eyeLookDownRight")))
        return FaceObservation.builder()
            .box(minX, minY, maxX - minX, maxY - minY)
            .pose(yaw, pitch, roll)
            .eyeAspect(ear(b("eyeBlinkLeft")), ear(b("eyeBlinkRight")))
            .mouthAspect(b("jawOpen") * 0.9)
            .smileScore((b("mouthSmileLeft") + b("mouthSmileRight")) / 2.0)
            .browRaiseScore(maxOf(b("browInnerUp"), (b("browOuterUpLeft") + b("browOuterUpRight")) / 2.0))
            .gaze(gazeX.coerceIn(-1.0, 1.0), gazeY.coerceIn(-1.0, 1.0))
            .landmarks(pts)
            .blendshapes(bs)
            .build()
    }

    /**
     * Euler angles (degrees) from MediaPipe's 4x4 column-major transformation matrix.
     * Returns [yaw, pitch, roll]; yaw positive when the subject turns to their own left.
     */
    internal fun eulerFromMatrix(m: FloatArray): DoubleArray {
        // Column-major: element (row r, col c) = m[c*4 + r]
        fun at(r: Int, c: Int) = m[c * 4 + r].toDouble()
        val r00 = at(0, 0); val r10 = at(1, 0); val r20 = at(2, 0)
        val r21 = at(2, 1); val r22 = at(2, 2)
        val sy = sqrt(r00 * r00 + r10 * r10)
        val pitch: Double; val yaw: Double; val roll: Double
        if (sy > 1e-6) {
            pitch = Math.toDegrees(atan2(r21, r22))
            yaw = Math.toDegrees(atan2(-r20, sy))
            roll = Math.toDegrees(atan2(r10, r00))
        } else {
            pitch = Math.toDegrees(atan2(-at(1, 2), at(1, 1)))
            yaw = Math.toDegrees(asin((-r20).coerceIn(-1.0, 1.0)))
            roll = 0.0
        }
        // MediaPipe's camera frame has +x to the image right; a subject turning to their own left
        // rotates the face towards image-right for an unmirrored front camera, which yields a
        // negative yaw in this decomposition. Flip so the API convention (+ = subject's left) holds.
        return doubleArrayOf(-yaw, -pitch, roll)
    }
}
