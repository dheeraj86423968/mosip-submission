package io.mosip.vivid.android.bridge

import io.mosip.vivid.core.session.LivenessOrchestrator
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.view.TextureRegistry
import io.mosip.vivid.android.bridge.gen.LivenessFlutterApi
import io.mosip.vivid.android.bridge.gen.LivenessHostApi
import io.mosip.vivid.android.bridge.gen.FaceMatchDto
import io.mosip.vivid.android.bridge.gen.LivenessResultDto
import io.mosip.vivid.android.bridge.gen.PolicySummaryDto
import io.mosip.vivid.android.camera.CameraXFrameSource
import io.mosip.vivid.android.engine.MediaPipeLivenessEngine
import io.mosip.vivid.android.face.EnrolledFaceProvider
import io.mosip.vivid.android.face.FaceMatchOutcome
import io.mosip.vivid.android.face.FaceMatchPolicy
import io.mosip.vivid.android.face.FaceMatcher
import io.mosip.vivid.android.face.StillFaceEmbedder
import android.graphics.Bitmap
import io.mosip.vivid.android.perf.ThermalGovernor
import io.mosip.vivid.android.policy.PolicyJson
import io.mosip.vivid.api.LivenessEngine
import io.mosip.vivid.api.SessionContext
import io.mosip.vivid.api.SessionState
import io.mosip.vivid.api.Workflow
import io.mosip.vivid.device.StreamOptions
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Pigeon host implementation: wires [CameraXFrameSource] + [MediaPipeLivenessEngine] + the
 * liveness-core orchestrator, runs everything off the main thread on a dedicated
 * [HandlerThread], and delivers [SessionState] snapshots to Flutter on the main thread.
 *
 * Registration (in the client's `MainActivity.configureFlutterEngine`):
 * ```
 * livenessPigeonApi.attach(this, flutterEngine.renderer, flutterEngine.dartExecutor.binaryMessenger)
 * LivenessHostApi.setUp(flutterEngine.dartExecutor.binaryMessenger, livenessPigeonApi)
 * ```
 * The engine is created lazily on first use (model cold start, PLAN 8.3: <= 1.5 s) and kept
 * for the life of the process; call [shutdown] from `Activity.onDestroy` if the process should
 * release the models.
 *
 * Host-app hooks (all optional, so the standalone build and tests need none of them):
 *  - [policySource]: per-workflow policy JSON from the host's configuration (ARC: global params
 *    `mosip.registration.liveness.<workflow>.*`). Used whenever Dart passes `"{}"` to `startSession`.
 *  - [enrolledFaces]: the user's enrolled face images, for [matchEnrolledFace] (operator login,
 *    supervisor authentication).
 *  - [audit]: `(event, sessionId, detail)`; event names are the `LIVENESS_*` audit events.
 *
 * Anchor embeddings of PASSED sessions are kept for [ANCHOR_TTL_MILLIS] after the session is released
 * so that [matchEnrolledFace] and [bindCapture] can still use them; nothing else is retained.
 */
class LivenessPigeonApi @JvmOverloads constructor(
    private val context: Context,
    private val engineFactory: (Context, ThermalGovernor) -> LivenessEngine = { c, g -> MediaPipeLivenessEngine(c, thermalGovernor = g) },
    private val online: () -> Boolean = { true },
    private val audit: (event: String, sessionId: String, detail: String) -> Unit = { _, _, _ -> },
    private val policySource: (workflow: String) -> String? = { null },
    private val enrolledFaces: EnrolledFaceProvider? = null
) : LivenessHostApi {

    companion object {
        private const val TAG = "LivenessPigeonApi"
        /** How long a PASSED session's anchor stays usable for face matching / capture binding. */
        const val ANCHOR_TTL_MILLIS = 5 * 60 * 1000L
        private const val MAX_RETAINED = 8
    }

    /** Anchor of a PASSED session, kept after the session is released. */
    private class Retained(val workflow: Workflow, val anchor: FloatArray?, val atMillis: Long)
    private val retained = object : LinkedHashMap<String, Retained>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Retained>?) = size > MAX_RETAINED
    }
    private val stillEmbedder by lazy { StillFaceEmbedder(context) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("vivid-liveness").apply { start() }
    private val workerHandler = Handler(worker.looper)
    private val thermal = ThermalGovernor(context)
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Active>()

    @Volatile private var engine: LivenessEngine? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var textures: TextureRegistry? = null
    private var flutterApi: LivenessFlutterApi? = null

    private inner class Active(
        val id: String,
        val session: LivenessOrchestrator,
        val source: CameraXFrameSource,
        val texture: TextureRegistry.SurfaceTextureEntry?,
        var bridge: CoreBindings.Closeable?
    ) {
        var lastState: SessionState = session.state()
        val ticker = object : Runnable {
            override fun run() {
                if (!sessions.containsKey(id)) return
                // Keep timers alive when the camera stalls; harmless otherwise.
                runCatching { session.tick() }.onFailure { Log.w(TAG, "tick failed", it) }
                workerHandler.postDelayed(this, 250)
            }
        }
    }

    /** Must be called once with the host activity (a LifecycleOwner) before the first session. */
    fun attach(owner: LifecycleOwner, textureRegistry: TextureRegistry?, messenger: BinaryMessenger) {
        lifecycleOwner = owner
        textures = textureRegistry
        flutterApi = LivenessFlutterApi(messenger)
    }

    /** Native-side accessor for the identity-continuity check against the signed rcapture image. */
    fun anchorEmbedding(sessionId: String): FloatArray? =
        sessions[sessionId]?.session?.let { runCatching { it.result().anchorEmbedding().orElse(null) }.getOrNull() }
            ?: synchronized(retained) { retained[sessionId]?.takeIf { fresh(it) }?.anchor }

    private fun canonical(name: String) = name.trim().uppercase(Locale.ROOT)
    private fun workflowOf(name: String): Workflow = Workflow.valueOf(canonical(name))

    private fun fresh(r: Retained) = System.currentTimeMillis() - r.atMillis <= ANCHOR_TTL_MILLIS

    /** Raw policy JSON for [workflow]: the host's configuration, or "{}" for compiled-in defaults. */
    private fun resolvePolicyJson(workflow: String, fromDart: String?): String {
        if (!fromDart.isNullOrBlank() && fromDart.trim() != "{}") return fromDart
        return runCatching { policySource(canonical(workflow)) }
            .onFailure { Log.w(TAG, "policy source failed; using defaults", it) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "{}"
    }

    /**
     * Capture binding: embeds the face in the signed `rcapture` image and compares it with the anchor of
     * the most recent [workflow] session that PASSED within [ANCHOR_TTL_MILLIS] (RESIDENT for resident
     * registration, OPERATOR for operator onboarding, whose capture becomes the operator's reference face).
     * The anchor is consumed: one liveness session binds one capture. Blocking; call off the main thread.
     */
    @JvmOverloads
    fun bindCapture(captureImage: Bitmap?, workflow: Workflow = Workflow.RESIDENT): FaceMatchOutcome {
        val policyJson = resolvePolicyJson(workflow.name, null)
        val livenessOn = runCatching { PolicyJson.parse(workflow, policyJson).enabled() }.getOrDefault(true)
        val fm = FaceMatchPolicy.parse(workflow, policyJson)
        if (!livenessOn || !fm.enabled) return FaceMatcher.decide(fm.copy(enabled = false), null, emptyList(), 0)
        val entry = synchronized(retained) {
            retained.entries.lastOrNull { it.value.workflow == workflow && fresh(it.value) }?.also { retained.remove(it.key) }
        }
        val anchor = entry?.value?.anchor
        val sessionId = entry?.key ?: ""
        val out = when {
            anchor == null -> FaceMatcher.decide(fm, null, emptyList(), 0)
            captureImage == null -> FaceMatchOutcome(FaceMatchOutcome.Status.NO_FACE, false, Double.NaN, fm.threshold, 0, "capture image not decodable")
            else -> when (val e = synchronized(stillEmbedder) { stillEmbedder.embed(captureImage) }) {
                is StillFaceEmbedder.Result.Ok -> FaceMatcher.decide(fm.copy(requireEnrolment = true), anchor, listOf(e.embedding), 1)
                is StillFaceEmbedder.Result.NoFace -> FaceMatchOutcome(FaceMatchOutcome.Status.NO_FACE, false, Double.NaN, fm.threshold, 0)
                is StillFaceEmbedder.Result.Failed -> FaceMatchOutcome(FaceMatchOutcome.Status.UNAVAILABLE, false, Double.NaN, fm.threshold, 0, e.reason)
            }
        }
        audit(if (out.accepted) "LIVENESS_CAPTURE_BOUND" else "LIVENESS_IDENTITY_DRIFT", sessionId, describe(out))
        return out
    }

    private fun describe(o: FaceMatchOutcome) =
        "${o.status} similarity=${if (o.similarity.isNaN()) "n/a" else String.format(Locale.ROOT, "%.3f", o.similarity)} " +
            "threshold=${String.format(Locale.ROOT, "%.2f", o.threshold)} refs=${o.referencesCompared}" + (o.detail?.let { " ($it)" } ?: "")

    // ---- LivenessHostApi -------------------------------------------------------------------

    override fun startSession(workflow: String, policyJson: String, assisted: Boolean, callback: (Result<String>) -> Unit) {
        workerHandler.post {
            try {
                val wf = workflowOf(workflow)
                val policy = PolicyJson.parse(wf, resolvePolicyJson(wf.name, policyJson))
                val eng = engine ?: engineFactory(context, thermal).also { engine = it }
                val id = UUID.randomUUID().toString()
                val nonce = ByteArray(16).also { random.nextBytes(it) }
                val ctx = SessionContext(wf, id, nonce, context.resources.configuration.locales[0], online(), assisted)
                val session = CoreBindings.openSession(eng, policy, ctx)
                val owner = lifecycleOwner ?: throw IllegalStateException("LivenessPigeonApi.attach() not called")
                val source = CameraXFrameSource(context, owner, thermalGovernor = thermal)
                val tex = textures?.createSurfaceTexture()
                if (tex != null) source.attachPreview(FlutterPreviewSurface(context, tex))
                val active = Active(id, session, source, tex, null)
                sessions[id] = active
                session.addListener { prev, cur ->
                    active.lastState = cur
                    if (prev.phase() != cur.phase() && cur.phase() == SessionState.Phase.PASSED) {
                        // Runs before any result() call Dart makes after seeing PASSED (same FIFO worker queue).
                        workerHandler.post { retainAnchor(id, wf, session) }
                    }
                    if (prev.phase() != cur.phase() && (cur.phase() == SessionState.Phase.PASSED || cur.phase() == SessionState.Phase.FAILED || cur.phase() == SessionState.Phase.LOCKED))
                        audit("LIVENESS_" + cur.phase().name, id, cur.failure().map { it.name }.orElse(""))
                    if (cur.isTerminal) workerHandler.post { teardownCamera(active) }
                    publish(id, cur, active)
                }
                active.bridge = CoreBindings.bridge(source, session, StreamOptions(thermal.targetFps, 2, 3000, 3))
                workerHandler.postDelayed(active.ticker, 250)
                audit("LIVENESS_SESSION_STARTED", id, wf.name)
                publish(id, session.state(), active)
                mainHandler.post { callback(Result.success(id)) }
            } catch (e: Exception) {
                Log.e(TAG, "startSession failed", e)
                mainHandler.post { callback(Result.failure(e)) }
            }
        }
    }

    override fun cancel(sessionId: String, callback: (Result<Unit>) -> Unit) {
        workerHandler.post {
            val a = sessions[sessionId]
            if (a != null) {
                runCatching { a.session.cancel() }.onFailure { Log.w(TAG, "cancel failed", it) }
                teardownCamera(a)
                publish(sessionId, a.session.state(), a)
            }
            mainHandler.post { callback(Result.success(Unit)) }
        }
    }

    override fun retry(sessionId: String, callback: (Result<Boolean>) -> Unit) {
        workerHandler.post {
            val a = sessions[sessionId]
            val ok = try {
                if (a == null) false else {
                    val allowed = a.session.retry()
                    if (allowed && a.bridge == null) {
                        // Camera was torn down after FAILED; bring it back for the next attempt.
                        a.bridge = CoreBindings.bridge(a.source, a.session, StreamOptions(thermal.targetFps, 2, 3000, 3))
                    }
                    if (allowed) publish(sessionId, a.session.state(), a)
                    allowed
                }
            } catch (e: Exception) { Log.e(TAG, "retry failed", e); false }
            mainHandler.post { callback(Result.success(ok)) }
        }
    }

    override fun result(sessionId: String, callback: (Result<LivenessResultDto>) -> Unit) {
        workerHandler.post {
            val a = sessions[sessionId]
            try {
                val r = a?.session?.result() ?: throw IllegalStateException("unknown session $sessionId")
                val dto = SessionDtoMapper.toDto(r)
                if (a.lastState.isTerminal) release(a)
                mainHandler.post { callback(Result.success(dto)) }
            } catch (e: Exception) {
                mainHandler.post { callback(Result.failure(e)) }
            }
        }
    }

    override fun previewTextureId(sessionId: String, callback: (Result<Long>) -> Unit) {
        val id = sessions[sessionId]?.texture?.id() ?: -1L
        callback(Result.success(id))
    }

    override fun policySummary(workflow: String, callback: (Result<PolicySummaryDto>) -> Unit) {
        workerHandler.post {
            val r = runCatching {
                val wf = workflowOf(workflow)
                val json = resolvePolicyJson(wf.name, null)
                val p = PolicyJson.parse(wf, json)
                val fm = FaceMatchPolicy.parse(wf, json)
                PolicySummaryDto(p.enabled(), fm.enabled, fm.threshold, fm.requireEnrolment, p.policyVersion())
            }
            mainHandler.post { callback(r) }
        }
    }

    override fun matchEnrolledFace(sessionId: String, userId: String, callback: (Result<FaceMatchDto>) -> Unit) {
        workerHandler.post {
            val r = runCatching {
                val wf = sessions[sessionId]?.session?.let { runCatching { it.result().workflow() }.getOrNull() }
                    ?: synchronized(retained) { retained[sessionId]?.workflow } ?: Workflow.OPERATOR
                val fm = FaceMatchPolicy.parse(wf, resolvePolicyJson(wf.name, null))
                val out = matchEnrolled(fm, anchorEmbedding(sessionId), userId)
                audit(if (out.accepted) "LIVENESS_FACE_MATCH_PASSED" else "LIVENESS_FACE_MATCH_FAILED", sessionId,
                    "${wf.name} ${describe(out)}")
                FaceMatchDto(out.status.name, out.accepted, if (out.similarity.isNaN()) -2.0 else out.similarity, out.threshold,
                    out.referencesCompared.toLong(), out.detail)
            }
            mainHandler.post { callback(r) }
        }
    }

    private fun matchEnrolled(fm: FaceMatchPolicy, anchor: FloatArray?, userId: String): FaceMatchOutcome {
        if (!fm.enabled || anchor == null) return FaceMatcher.decide(fm, anchor, emptyList(), 0)
        val provider = enrolledFaces
            ?: return FaceMatchOutcome(FaceMatchOutcome.Status.UNAVAILABLE, false, Double.NaN, fm.threshold, 0, "no EnrolledFaceProvider")
        val enrolled = try { provider.enrolledFaces(userId) } catch (e: Exception) {
            Log.e(TAG, "enrolled face lookup failed", e)
            return FaceMatchOutcome(FaceMatchOutcome.Status.UNAVAILABLE, false, Double.NaN, fm.threshold, 0, "enrolled face lookup failed")
        }
        val refs = ArrayList<FloatArray>()
        var failure: String? = null
        for (img in enrolled.images) {
            when (val e = synchronized(stillEmbedder) { stillEmbedder.embed(img) }) {
                is StillFaceEmbedder.Result.Ok -> refs += e.embedding
                is StillFaceEmbedder.Result.NoFace -> failure = failure ?: "no face in enrolled image"
                is StillFaceEmbedder.Result.Failed -> failure = e.reason
            }
        }
        // Models missing is an engine problem, not an enrolment problem: fail closed.
        if (refs.isEmpty() && enrolled.images.isNotEmpty() && failure != null && failure != "no face in enrolled image")
            return FaceMatchOutcome(FaceMatchOutcome.Status.UNAVAILABLE, false, Double.NaN, fm.threshold, 0, failure)
        return FaceMatcher.decide(fm, anchor, refs, enrolled.records).let { if (refs.isEmpty() && failure != null) it.copy(detail = failure) else it }
    }

    private fun retainAnchor(id: String, wf: Workflow, session: LivenessOrchestrator) {
        val anchor = runCatching { session.result().anchorEmbedding().orElse(null) }.getOrNull()
        synchronized(retained) { retained[id] = Retained(wf, anchor, System.currentTimeMillis()) }
    }

    /** Releases all sessions, the camera and the models. */
    fun shutdown() {
        workerHandler.post {
            for (a in sessions.values) { runCatching { a.session.cancel() }; release(a) }
            engine?.close(); engine = null
            synchronized(stillEmbedder) { stillEmbedder.close() }
            synchronized(retained) { retained.clear() }
            thermal.stop()
        }
    }

    // ---- internals -------------------------------------------------------------------------

    private fun publish(id: String, state: SessionState, a: Active) {
        val dto = SessionDtoMapper.toDto(state, a.source.health(), null)
        mainHandler.post {
            // Pigeon 10.x FlutterApi void callbacks carry no Result; delivery errors are not surfaced.
            flutterApi?.onState(id, dto) { }
        }
    }

    private fun teardownCamera(a: Active) {
        a.bridge?.close(); a.bridge = null
        a.source.stop()
    }

    private fun release(a: Active) {
        workerHandler.removeCallbacks(a.ticker)
        teardownCamera(a)
        runCatching { a.source.close() }
        runCatching { a.texture?.release() }
        runCatching { a.session.close() }
        sessions.remove(a.id)
    }
}
