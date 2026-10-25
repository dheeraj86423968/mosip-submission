package io.mosip.vivid.android.camera

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Converts a CameraX [ImageProxy] in YUV_420_888 to a packed NV21 buffer (Y plane, then
 * interleaved V,U), honouring row and pixel strides, then optionally rotates it to upright.
 * All output buffers are reused per instance; the caller must copy if it keeps the frame.
 */
class YuvToNv21 {
    private var nv21: ByteArray = ByteArray(0)
    private var rotated: ByteArray = ByteArray(0)

    /** Result of a conversion; [data] is valid until the next call. */
    class Result(val data: ByteArray, val width: Int, val height: Int)

    fun convert(image: ImageProxy, rotationDegrees: Int): Result {
        require(image.format == ImageFormat.YUV_420_888) { "expected YUV_420_888, got ${image.format}" }
        val w = image.width
        val h = image.height
        val size = w * h * 3 / 2
        if (nv21.size != size) nv21 = ByteArray(size)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, w, h, nv21, 0, 1)

        val chromaW = w / 2
        val chromaH = h / 2
        val uvOffset = w * h
        if (vPlane.pixelStride == 2 && uPlane.pixelStride == 2 && vPlane.rowStride == uPlane.rowStride
            && isInterleavedVU(uPlane.buffer, vPlane.buffer)) {
            // Fast path: V and U share one interleaved buffer (most Camera2 HALs). Copy V-first rows.
            val vb = vPlane.buffer
            val rowStride = vPlane.rowStride
            val rowBytes = chromaW * 2
            for (row in 0 until chromaH) {
                vb.position(row * rowStride)
                val toCopy = minOf(rowBytes, vb.remaining())
                vb.get(nv21, uvOffset + row * rowBytes, toCopy)
            }
        } else {
            // Generic path: interleave V and U by hand.
            copyPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, chromaW, chromaH, nv21, uvOffset, 2)
            copyPlane(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, chromaW, chromaH, nv21, uvOffset + 1, 2)
        }

        return when (rotationDegrees) {
            0 -> Result(nv21, w, h)
            90, 180, 270 -> rotate(nv21, w, h, rotationDegrees)
            else -> Result(nv21, w, h)
        }
    }

    private fun isInterleavedVU(u: ByteBuffer, v: ByteBuffer): Boolean {
        // On an interleaved layout the U buffer starts one byte after the V buffer.
        return try {
            u.capacity() + 1 == v.capacity() || v.capacity() + 1 == u.capacity() || u.capacity() == v.capacity()
        } catch (_: Exception) { false }
    }

    private fun copyPlane(src: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int,
                          dst: ByteArray, dstOffset: Int, dstStride: Int) {
        src.rewind()
        var out = dstOffset
        if (pixelStride == 1 && dstStride == 1) {
            for (row in 0 until height) {
                src.position(row * rowStride)
                src.get(dst, out, width)
                out += width
            }
            return
        }
        val rowBuf = ByteArray(rowStride)
        for (row in 0 until height) {
            src.position(row * rowStride)
            val len = minOf(rowStride, src.remaining())
            src.get(rowBuf, 0, len)
            var i = 0
            for (col in 0 until width) {
                dst[out] = rowBuf[i]
                out += dstStride
                i += pixelStride
            }
        }
    }

    private fun rotate(src: ByteArray, w: Int, h: Int, degrees: Int): Result {
        val size = w * h * 3 / 2
        if (rotated.size != size) rotated = ByteArray(size)
        val dst = rotated
        val (ow, oh) = if (degrees == 180) w to h else h to w
        // Luma
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (nx, ny) = map(x, y, w, h, degrees)
                dst[ny * ow + nx] = src[y * w + x]
            }
        }
        // Chroma (VU pairs at half resolution)
        val cw = w / 2; val ch = h / 2
        val ocw = ow / 2
        val srcOff = w * h; val dstOff = ow * oh
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                val (nx, ny) = map(x, y, cw, ch, degrees)
                val s = srcOff + (y * cw + x) * 2
                val d = dstOff + (ny * ocw + nx) * 2
                dst[d] = src[s]
                dst[d + 1] = src[s + 1]
            }
        }
        return Result(dst, ow, oh)
    }

    /** Maps a source pixel to its position in the clockwise-rotated output. */
    private fun map(x: Int, y: Int, w: Int, h: Int, degrees: Int): Pair<Int, Int> = when (degrees) {
        90 -> (h - 1 - y) to x
        180 -> (w - 1 - x) to (h - 1 - y)
        270 -> y to (w - 1 - x)
        else -> x to y
    }
}
