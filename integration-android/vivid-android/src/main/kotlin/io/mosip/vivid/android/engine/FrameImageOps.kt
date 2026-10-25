package io.mosip.vivid.android.engine

import io.mosip.vivid.device.FaceFrame
import io.mosip.vivid.device.PixelFormat
import java.nio.ByteBuffer

/**
 * Small, allocation-conscious pixel helpers for NV21 / RGB888 frames. Everything here is plain
 * Kotlin so it is unit-testable on the JVM; GPU/libyuv paths can replace it later.
 */
object FrameImageOps {

    /** A packed RGB crop (3 bytes per pixel, row-major) with its size. */
    class Rgb(val data: ByteArray, val width: Int, val height: Int) {
        fun r(x: Int, y: Int) = data[(y * width + x) * 3].toInt() and 0xff
        fun g(x: Int, y: Int) = data[(y * width + x) * 3 + 1].toInt() and 0xff
        fun b(x: Int, y: Int) = data[(y * width + x) * 3 + 2].toInt() and 0xff
    }

    /** Grayscale plane, one byte per pixel. */
    class Gray(val data: ByteArray, val width: Int, val height: Int) {
        operator fun get(x: Int, y: Int) = data[y * width + x].toInt() and 0xff
    }

    /** Full-frame conversion to RGBA (what MediaPipe's ByteBufferImageBuilder accepts). Reuses [out] when large enough. */
    fun toRgba(frame: FaceFrame, out: ByteBuffer?): ByteBuffer {
        val w = frame.width(); val h = frame.height()
        val need = w * h * 4
        val dst = if (out != null && out.capacity() >= need) out.apply { clear() } else ByteBuffer.allocateDirect(need)
        val src = frame.pixels()
        when (frame.format()) {
            PixelFormat.NV21 -> {
                val y = ByteArray(w * h); src.get(y)
                val vu = ByteArray(w * h / 2); src.get(vu)
                for (row in 0 until h) {
                    val cRow = (row shr 1) * w
                    for (col in 0 until w) {
                        val yy = (y[row * w + col].toInt() and 0xff)
                        val ci = cRow + (col and 1.inv())
                        val v = (vu[ci].toInt() and 0xff) - 128
                        val u = (vu[ci + 1].toInt() and 0xff) - 128
                        dst.put(clamp(yy + ((91881 * v) shr 16)).toByte())
                        dst.put(clamp(yy - ((22554 * u + 46802 * v) shr 16)).toByte())
                        dst.put(clamp(yy + ((116130 * u) shr 16)).toByte())
                        dst.put(0xff.toByte())
                    }
                }
            }
            PixelFormat.RGB888 -> { val px = ByteArray(3); for (i in 0 until w * h) { src.get(px); dst.put(px); dst.put(0xff.toByte()) } }
            PixelFormat.BGR888 -> { val px = ByteArray(3); for (i in 0 until w * h) { src.get(px); dst.put(px[2]); dst.put(px[1]); dst.put(px[0]); dst.put(0xff.toByte()) } }
            PixelFormat.GRAY8 -> { for (i in 0 until w * h) { val g = src.get(); dst.put(g); dst.put(g); dst.put(g); dst.put(0xff.toByte()) } }
            else -> throw IllegalArgumentException("unsupported pixel format ${frame.format()} (MJPEG/YUV420/GRAY16 need a decoder)")
        }
        dst.rewind()
        return dst
    }

    /**
     * Crops a rectangle (pixel coords, clipped to the frame) and resizes to [outW]x[outH] with
     * bilinear sampling, producing packed RGB. Works directly on NV21 so no full-frame conversion.
     */
    fun cropRgb(frame: FaceFrame, x0: Int, y0: Int, x1: Int, y1: Int, outW: Int, outH: Int): Rgb {
        val w = frame.width(); val h = frame.height()
        val cx0 = x0.coerceIn(0, w - 1); val cy0 = y0.coerceIn(0, h - 1)
        val cx1 = x1.coerceIn(cx0 + 1, w); val cy1 = y1.coerceIn(cy0 + 1, h)
        val src = frame.pixels()
        val out = ByteArray(outW * outH * 3)
        val sx = (cx1 - cx0).toFloat() / outW
        val sy = (cy1 - cy0).toFloat() / outH
        val rgb = IntArray(3)
        var o = 0
        for (oy in 0 until outH) {
            val fy = cy0 + (oy + 0.5f) * sy - 0.5f
            for (ox in 0 until outW) {
                val fx = cx0 + (ox + 0.5f) * sx - 0.5f
                sampleBilinear(frame.format(), src, w, h, fx, fy, rgb)
                out[o++] = rgb[0].toByte(); out[o++] = rgb[1].toByte(); out[o++] = rgb[2].toByte()
            }
        }
        return Rgb(out, outW, outH)
    }

    fun toGray(rgb: Rgb): Gray {
        val g = ByteArray(rgb.width * rgb.height)
        for (i in g.indices) {
            val r = rgb.data[i * 3].toInt() and 0xff; val gg = rgb.data[i * 3 + 1].toInt() and 0xff; val b = rgb.data[i * 3 + 2].toInt() and 0xff
            g[i] = ((r * 77 + gg * 150 + b * 29) shr 8).toByte()
        }
        return Gray(g, rgb.width, rgb.height)
    }

    /** Variance of the 3x3 Laplacian, normalised to [0,1] with a soft knee; the usual sharpness proxy. */
    fun laplacianSharpness(g: Gray): Double {
        if (g.width < 3 || g.height < 3) return 0.0
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (y in 1 until g.height - 1) for (x in 1 until g.width - 1) {
            val l = 4 * g[x, y] - g[x - 1, y] - g[x + 1, y] - g[x, y - 1] - g[x, y + 1]
            sum += l; sumSq += l.toDouble() * l; n++
        }
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        // ~300 is a crisp 480p face; ~50 is motion-blurred.
        return (variance / 300.0).coerceIn(0.0, 1.0)
    }

    /** Mean luma mapped so 0.3..0.7 of full scale is ideal (1.0) and the extremes fall to 0. */
    fun illuminationQuality(g: Gray): Double {
        var sum = 0L
        for (b in g.data) sum += (b.toInt() and 0xff)
        val mean = sum.toDouble() / g.data.size / 255.0
        return when {
            mean < 0.3 -> (mean / 0.3).coerceIn(0.0, 1.0)
            mean > 0.7 -> ((1.0 - mean) / 0.3).coerceIn(0.0, 1.0)
            else -> 1.0
        }
    }

    private fun sampleBilinear(fmt: PixelFormat, src: ByteBuffer, w: Int, h: Int, fx: Float, fy: Float, out: IntArray) {
        val x0 = fx.toInt().coerceIn(0, w - 1); val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1); val y1 = (y0 + 1).coerceAtMost(h - 1)
        val ax = (fx - x0).coerceIn(0f, 1f); val ay = (fy - y0).coerceIn(0f, 1f)
        val p = IntArray(3); val acc = FloatArray(3)
        val weights = floatArrayOf((1 - ax) * (1 - ay), ax * (1 - ay), (1 - ax) * ay, ax * ay)
        val xs = intArrayOf(x0, x1, x0, x1); val ys = intArrayOf(y0, y0, y1, y1)
        for (k in 0 until 4) {
            pixel(fmt, src, w, h, xs[k], ys[k], p)
            acc[0] += weights[k] * p[0]; acc[1] += weights[k] * p[1]; acc[2] += weights[k] * p[2]
        }
        out[0] = clamp(acc[0].toInt()); out[1] = clamp(acc[1].toInt()); out[2] = clamp(acc[2].toInt())
    }

    private fun pixel(fmt: PixelFormat, src: ByteBuffer, w: Int, h: Int, x: Int, y: Int, out: IntArray) {
        when (fmt) {
            PixelFormat.NV21 -> {
                val yy = src.get(y * w + x).toInt() and 0xff
                val ci = w * h + (y shr 1) * w + (x and 1.inv())
                val v = (src.get(ci).toInt() and 0xff) - 128
                val u = (src.get(ci + 1).toInt() and 0xff) - 128
                out[0] = clamp(yy + ((91881 * v) shr 16))
                out[1] = clamp(yy - ((22554 * u + 46802 * v) shr 16))
                out[2] = clamp(yy + ((116130 * u) shr 16))
            }
            PixelFormat.RGB888 -> { val i = (y * w + x) * 3; out[0] = src.get(i).toInt() and 0xff; out[1] = src.get(i + 1).toInt() and 0xff; out[2] = src.get(i + 2).toInt() and 0xff }
            PixelFormat.BGR888 -> { val i = (y * w + x) * 3; out[2] = src.get(i).toInt() and 0xff; out[1] = src.get(i + 1).toInt() and 0xff; out[0] = src.get(i + 2).toInt() and 0xff }
            PixelFormat.GRAY8 -> { val g = src.get(y * w + x).toInt() and 0xff; out[0] = g; out[1] = g; out[2] = g }
            else -> throw IllegalArgumentException("unsupported pixel format $fmt")
        }
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
}
