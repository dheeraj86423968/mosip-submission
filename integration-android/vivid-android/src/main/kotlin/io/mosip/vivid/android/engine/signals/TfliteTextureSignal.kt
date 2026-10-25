package io.mosip.vivid.android.engine.signals

import android.util.Log
import io.mosip.vivid.android.engine.ModelAssets
import io.mosip.vivid.api.SignalScore
import io.mosip.vivid.api.Signals
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * S1: texture CNN. MiniFASNet-V2 (int8, 80x80 RGB input at scale 2.7 around the face box),
 * 3-way softmax where index 1 = live, 0/2 = attack (print / replay families).
 *
 * GPU delegate is tried first (int8 models run on GPU through dequantisation; if the delegate
 * rejects the graph we fall back to CPU/XNNPACK with 2 threads, which is the A53-class budget).
 * Runs every [everyN] frames (set by the engine from [io.mosip.vivid.android.perf.ThermalGovernor])
 * and otherwise repeats the last score with decaying confidence.
 */
class TfliteTextureSignal(assets: ModelAssets, preferGpu: Boolean = true, cpuThreads: Int = 2) : LivenessSignal {
    override val id: String = Signals.CNN_TEXTURE

    companion object { private const val TAG = "TfliteTextureSignal"; const val INPUT = 80 }

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private val inputIsQuantised: Boolean
    private val inputScale: Float
    private val inputZero: Int
    private val outputIsQuantised: Boolean
    private val outputScale: Float
    private val outputZero: Int
    private val inputBuffer: ByteBuffer
    private val outputQuant = Array(1) { ByteArray(3) }
    private val outputFloat = Array(1) { FloatArray(3) }

    var everyN: Int = 1
    private var last: SignalScore? = null
    private var sinceLast = 0

    init {
        val model = assets.mapped(ModelAssets.KEY_TEXTURE)
        var built: Interpreter? = null
        if (preferGpu) {
            try {
                val compat = CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    val d = GpuDelegate(compat.bestOptionsForThisDevice)
                    built = Interpreter(model, Interpreter.Options().addDelegate(d))
                    gpuDelegate = d
                    Log.i(TAG, "texture model on GPU delegate")
                }
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate unavailable, falling back to CPU: ${e.message}")
                gpuDelegate?.close(); gpuDelegate = null; built = null
            }
        }
        interpreter = built ?: Interpreter(model, Interpreter.Options().setNumThreads(cpuThreads).setUseXNNPACK(true))
        val inT = interpreter.getInputTensor(0)
        val outT = interpreter.getOutputTensor(0)
        inputIsQuantised = inT.dataType() == org.tensorflow.lite.DataType.UINT8 || inT.dataType() == org.tensorflow.lite.DataType.INT8
        inputScale = inT.quantizationParams().scale; inputZero = inT.quantizationParams().zeroPoint
        outputIsQuantised = outT.dataType() == org.tensorflow.lite.DataType.UINT8 || outT.dataType() == org.tensorflow.lite.DataType.INT8
        outputScale = outT.quantizationParams().scale; outputZero = outT.quantizationParams().zeroPoint
        val bytesPerValue = if (inputIsQuantised) 1 else 4
        inputBuffer = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * bytesPerValue).order(ByteOrder.nativeOrder())
    }

    override fun score(input: SignalInput): SignalScore? {
        sinceLast++
        if (last != null && sinceLast < everyN) {
            // Repeat with decayed confidence so fusion does not over-trust a stale frame.
            val l = last!!
            return SignalScore(l.signalId(), l.attackProbability(), l.confidence() * 0.8, false, l.attackClass())
        }
        sinceLast = 0
        val crop = input.faceCrop80Loose
        inputBuffer.rewind()
        if (inputIsQuantised) {
            // Model expects raw 0..255 pixels (asymmetric uint8) -> write bytes; for int8 shift by zero point.
            val signed = inputZero != 0 && interpreter.getInputTensor(0).dataType() == org.tensorflow.lite.DataType.INT8
            for (b in crop.data) inputBuffer.put(if (signed) ((b.toInt() and 0xff) - 128).toByte() else b)
        } else {
            for (b in crop.data) inputBuffer.putFloat((b.toInt() and 0xff) / 255f)
        }
        inputBuffer.rewind()
        val probs = FloatArray(3)
        try {
            if (outputIsQuantised) {
                interpreter.run(inputBuffer, outputQuant)
                for (i in 0 until 3) probs[i] = ((outputQuant[0][i].toInt() and 0xff) - outputZero) * outputScale
            } else {
                interpreter.run(inputBuffer, outputFloat)
                for (i in 0 until 3) probs[i] = outputFloat[0][i]
            }
        } catch (e: Exception) {
            Log.e(TAG, "inference failed", e)
            return SignalScore.of(id, 0.5, 0.0)
        }
        softmaxInPlace(probs)
        val live = probs[1].toDouble()
        val attack = 1.0 - live
        val attackClass = if (attack > 0.5) (if (probs[0] >= probs[2]) Signals.ATTACK_PRINT else Signals.ATTACK_SCREEN) else null
        // Confidence: how peaked the distribution is.
        val conf = (2.0 * kotlin.math.abs(live - 0.5)).coerceIn(0.3, 1.0)
        val s = SignalScore(id, attack, conf, attack > 0.97 && conf > 0.9, attackClass)
        last = s
        return s
    }

    private fun softmaxInPlace(v: FloatArray) {
        // The exported MiniFASNet already ends in softmax for most conversions; re-applying softmax to
        // probabilities is nearly idempotent, but logits must be normalised, so detect by range.
        val sum = v.sum()
        if (sum in 0.98f..1.02f && v.all { it in 0f..1f }) return
        val max = v.max()
        var s = 0f
        for (i in v.indices) { v[i] = kotlin.math.exp(v[i] - max); s += v[i] }
        for (i in v.indices) v[i] /= s
    }

    override fun reset() { last = null; sinceLast = 0 }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
