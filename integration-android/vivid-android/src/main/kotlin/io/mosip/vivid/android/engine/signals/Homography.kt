package io.mosip.vivid.android.engine.signals

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Least-squares planar homography (DLT with normalisation) and its re-projection residual.
 * Used by [ParallaxSignal]: a planar attack (print, screen) is explained almost perfectly by a
 * homography during a head turn; a real 3D face leaves a large residual.
 */
object Homography {

    /** Returns a 3x3 row-major homography mapping src -> dst, or null if degenerate. */
    fun estimate(src: DoubleArray, dst: DoubleArray): DoubleArray? {
        val n = src.size / 2
        if (n < 4 || dst.size != src.size) return null
        val (sN, sT) = normalise(src)
        val (dN, dT) = normalise(dst)
        // Build A (2n x 9) and solve A h = 0 via the smallest eigenvector of A^T A (Jacobi).
        val ata = Array(9) { DoubleArray(9) }
        val row = DoubleArray(9)
        for (i in 0 until n) {
            val x = sN[2 * i]; val y = sN[2 * i + 1]; val u = dN[2 * i]; val v = dN[2 * i + 1]
            fill(row, -x, -y, -1.0, 0.0, 0.0, 0.0, u * x, u * y, u); accumulate(ata, row)
            fill(row, 0.0, 0.0, 0.0, -x, -y, -1.0, v * x, v * y, v); accumulate(ata, row)
        }
        val h = smallestEigenvector(ata) ?: return null
        // Denormalise: H = dT^-1 * Hn * sT
        val hn = h
        val dTinv = invert3(dT) ?: return null
        val out = multiply(multiply(dTinv, hn), sT)
        val scale = out[8]
        if (abs(scale) < 1e-12) return null
        for (i in out.indices) out[i] /= scale
        return out
    }

    /** RMS of |H*src - dst| in the same units as the points. */
    fun residual(h: DoubleArray, src: DoubleArray, dst: DoubleArray): Double {
        val n = src.size / 2
        var sum = 0.0
        for (i in 0 until n) {
            val x = src[2 * i]; val y = src[2 * i + 1]
            val w = h[6] * x + h[7] * y + h[8]
            if (abs(w) < 1e-12) return Double.MAX_VALUE
            val px = (h[0] * x + h[1] * y + h[2]) / w
            val py = (h[3] * x + h[4] * y + h[5]) / w
            val dx = px - dst[2 * i]; val dy = py - dst[2 * i + 1]
            sum += dx * dx + dy * dy
        }
        return sqrt(sum / n)
    }

    private fun fill(r: DoubleArray, vararg v: Double) { for (i in 0 until 9) r[i] = v[i] }
    private fun accumulate(ata: Array<DoubleArray>, r: DoubleArray) { for (i in 0 until 9) for (j in 0 until 9) ata[i][j] += r[i] * r[j] }

    private fun normalise(p: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = p.size / 2
        var cx = 0.0; var cy = 0.0
        for (i in 0 until n) { cx += p[2 * i]; cy += p[2 * i + 1] }
        cx /= n; cy /= n
        var d = 0.0
        for (i in 0 until n) { val dx = p[2 * i] - cx; val dy = p[2 * i + 1] - cy; d += sqrt(dx * dx + dy * dy) }
        d /= n
        val s = if (d < 1e-12) 1.0 else sqrt(2.0) / d
        val out = DoubleArray(p.size)
        for (i in 0 until n) { out[2 * i] = (p[2 * i] - cx) * s; out[2 * i + 1] = (p[2 * i + 1] - cy) * s }
        val t = doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)
        return out to t
    }

    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val c = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) { var s = 0.0; for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]; c[i * 3 + j] = s }
        return c
    }

    private fun invert3(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-15) return null
        val inv = doubleArrayOf(
            e * i - f * h, c * h - b * i, b * f - c * e,
            f * g - d * i, a * i - c * g, c * d - a * f,
            d * h - e * g, b * g - a * h, a * e - b * d)
        for (k in inv.indices) inv[k] /= det
        return inv
    }

    /** Cyclic Jacobi eigen-decomposition of a symmetric 9x9; returns the eigenvector of the smallest eigenvalue. */
    private fun smallestEigenvector(a: Array<DoubleArray>): DoubleArray? {
        val n = 9
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        val m = Array(n) { a[it].clone() }
        for (sweep in 0 until 60) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += m[p][q] * m[p][q]
            if (off < 1e-20) break
            for (p in 0 until n) for (q in p + 1 until n) {
                if (abs(m[p][q]) < 1e-300) continue
                val theta = (m[q][q] - m[p][p]) / (2 * m[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0 until n) {
                    val mkp = m[k][p]; val mkq = m[k][q]
                    m[k][p] = c * mkp - s * mkq; m[k][q] = s * mkp + c * mkq
                }
                for (k in 0 until n) {
                    val mpk = m[p][k]; val mqk = m[q][k]
                    m[p][k] = c * mpk - s * mqk; m[q][k] = s * mpk + c * mqk
                }
                for (k in 0 until n) {
                    val vkp = v[k][p]; val vkq = v[k][q]
                    v[k][p] = c * vkp - s * vkq; v[k][q] = s * vkp + c * vkq
                }
            }
        }
        var best = 0
        for (i in 1 until n) if (m[i][i] < m[best][best]) best = i
        val out = DoubleArray(n) { v[it][best] }
        if (out.all { abs(it) < 1e-12 }) return null
        return out
    }
}
