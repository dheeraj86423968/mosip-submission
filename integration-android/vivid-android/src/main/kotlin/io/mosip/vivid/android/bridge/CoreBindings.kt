package io.mosip.vivid.android.bridge

import io.mosip.vivid.api.LivenessEngine
import io.mosip.vivid.api.LivenessPolicy
import io.mosip.vivid.api.SessionContext
import io.mosip.vivid.core.bridge.FrameSourceBridge
import io.mosip.vivid.core.session.LivenessOrchestrator
import io.mosip.vivid.core.session.LivenessSessionFactory
import io.mosip.vivid.device.FaceFrameSource
import io.mosip.vivid.device.StreamOptions

/**
 * The only file that touches `liveness-core`. Signatures confirmed against the built core (see NOTES-core.md):
 *   LivenessSessionFactory(engine: LivenessEngine)
 *   LivenessSessionFactory.open(policy: LivenessPolicy, ctx: SessionContext): LivenessOrchestrator
 *   FrameSourceBridge(source: FaceFrameSource, session: LivenessOrchestrator)
 *   FrameSourceBridge.start(options: StreamOptions) / stop()
 * The bridge needs the orchestrator type, not the LivenessSession interface, because it drives device
 * lifecycle callbacks (deviceReady, deviceReconnecting, deviceFault) that the frozen interface does not expose.
 */
internal object CoreBindings {

    fun openSession(engine: LivenessEngine, policy: LivenessPolicy, ctx: SessionContext): LivenessOrchestrator =
        LivenessSessionFactory(engine).open(policy, ctx)

    fun bridge(source: FaceFrameSource, session: LivenessOrchestrator, options: StreamOptions): Closeable {
        val b = FrameSourceBridge(source, session)
        b.start(options)
        return Closeable { b.stop() }
    }

    fun interface Closeable { fun close() }
}
