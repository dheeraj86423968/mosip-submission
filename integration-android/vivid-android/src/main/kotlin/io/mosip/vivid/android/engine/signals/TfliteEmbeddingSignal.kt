package io.mosip.vivid.android.engine.signals

import android.util.Log
import io.mosip.vivid.android.engine.FrameImageOps
import io.mosip.vivid.android.engine.ModelAssets
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * S7 support: face identity embedding (MobileFaceNet, 112x112 RGB, (x-127.5)/128 normalisation,
 * 128- or 512-d output, L2-normalised here). The orchestrator uses the embeddings for identity
 * continuity across the session and as the anchor compared against the signed `rcapture` image.
 * Not a [LivenessSignal]: it produces a vector, not an attack probability.
 */
class TfliteEmbeddingSignal(assets: ModelAssets, preferGpu: Boolean = false, cpuThreads: Int = 2, var everyN: Int = 3) {
    companion object { private const val TAG = "TfliteEmbeddingSignal"; const val INPUT = 112 }

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    val dimension: Int
    private val input = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
    private val output: Array<FloatArray>
    private var sinceLast = 0
    private var last: FloatArray? = null

    init {
        val model = assets.mapped(ModelAssets.KEY_EMBEDDING)
        var built: Interpreter? = null
        if (preferGpu) {
            try {
                val compat = CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    val d = GpuDelegate(compat.bestOptionsForThisDevice)
                    built = Interpreter(model, Interpreter.Options().addDelegate(d)); gpuDelegate = d
                }
            } catch (e: Exception) { Log.w(TAG, "GPU delegate unavailable: ${e.message}"); gpuDelegate?.close(); gpuDelegate = null; built = null }
        }
        interpreter = built ?: Interpreter(model, Interpreter.Options().setNumThreads(cpuThreads).setUseXNNPACK(true))
        dimension = interpreter.getOutputTensor(0).shape().last()
        output = Array(1) { FloatArray(dimension) }
    }

    /** Returns a fresh L2-normalised embedding every [everyN] calls, otherwise the last one (or null). */
    fun embed(crop: FrameImageOps.Rgb): FloatArray? {
        sinceLast++
        if (last != null && sinceLast < everyN) return last
        sinceLast = 0
        require(crop.width == INPUT && crop.height == INPUT) { "embedding crop must be ${INPUT}x$INPUT" }
        input.rewind()
        for (b in crop.data) input.putFloat(((b.toInt() and 0xff) - 127.5f) / 128f)
        input.rewind()
        return try {
            interpreter.run(input, output)
            val v = output[0].clone()
            var n = 0f; for (x in v) n += x * x
            val inv = 1f / sqrt(n).coerceAtLeast(1e-6f)
            for (i in v.indices) v[i] *= inv
            last = v
            v
        } catch (e: Exception) { Log.e(TAG, "embedding failed", e); null }
    }

    fun reset() { sinceLast = 0; last = null }
    fun close() { interpreter.close(); gpuDelegate?.close() }
}
