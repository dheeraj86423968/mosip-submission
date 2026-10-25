package io.mosip.vivid.android.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import io.mosip.vivid.android.engine.FrameImageOps
import io.mosip.vivid.android.engine.ModelAssets
import io.mosip.vivid.android.engine.signals.TfliteEmbeddingSignal

/**
 * Embeds the face in a still image (an enrolled ISO 19794-5 portrait or a signed `rcapture` image) with
 * the same models and the same crop geometry as the live engine, so the result is comparable with a
 * session's anchor embedding:
 *
 *  1. MediaPipe FaceLandmarker (IMAGE mode, CPU, 1 face) -> landmark bounding box.
 *  2. Tight square crop, side = max(box w, box h), centred on the box, clipped to the image
 *     (identical to `MediaPipeLivenessEngine.Analyzer.analyze`), resized to 112x112.
 *  3. MobileFaceNet via [TfliteEmbeddingSignal] (own instance, every call fresh), L2-normalised.
 *
 * Models are loaded lazily on first use and kept until [close]. Not thread-safe: call from one thread
 * (the bridge uses its worker thread).
 */
class StillFaceEmbedder(private val context: Context, private val assetsLoader: () -> ModelAssets = { ModelAssets.load(context) }) {

    companion object { private const val TAG = "StillFaceEmbedder" }

    sealed class Result {
        class Ok(val embedding: FloatArray) : Result()
        object NoFace : Result()
        class Failed(val reason: String) : Result()
    }

    private var landmarker: FaceLandmarker? = null
    private var embedder: TfliteEmbeddingSignal? = null

    private fun ensureModels() {
        if (landmarker != null && embedder != null) return
        val assets = assetsLoader()
        if (landmarker == null) {
            val base = BaseOptions.builder().setDelegate(Delegate.CPU)
            val path = assets.assetPath(ModelAssets.KEY_FACE_LANDMARKER)
            if (path != null) base.setModelAssetPath(path) else base.setModelAssetBuffer(assets.bytes(ModelAssets.KEY_FACE_LANDMARKER))
            landmarker = FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base.build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .build())
        }
        if (embedder == null) embedder = TfliteEmbeddingSignal(assets, preferGpu = false, cpuThreads = 2, everyN = 1)
    }

    fun embed(source: Bitmap): Result {
        val bmp = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
            ?: return Result.Failed("bitmap conversion failed")
        return try {
            ensureModels()
            val r = landmarker!!.detect(BitmapImageBuilder(bmp).build())
            val faces = r.faceLandmarks()
            if (faces.isEmpty()) return Result.NoFace
            var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
            for (p in faces[0]) {
                if (p.x() < minX) minX = p.x(); if (p.y() < minY) minY = p.y()
                if (p.x() > maxX) maxX = p.x(); if (p.y() > maxY) maxY = p.y()
            }
            val w = bmp.width; val h = bmp.height
            val cx = (minX + maxX) / 2f * w; val cy = (minY + maxY) / 2f * h
            val side = maxOf((maxX - minX) * w, (maxY - minY) * h)
            val crop = cropRgb(bmp, (cx - side / 2).toInt(), (cy - side / 2).toInt(), (cx + side / 2).toInt(), (cy + side / 2).toInt())
            val e = embedder!!
            e.reset()
            val v = e.embed(crop) ?: return Result.Failed("embedding model returned no output")
            Result.Ok(v)
        } catch (e: Exception) {
            Log.e(TAG, "still-image embedding failed", e)
            Result.Failed(e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    /** Same clipping rule as [FrameImageOps.cropRgb]: the rectangle is clipped to the image, then resized. */
    private fun cropRgb(src: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): FrameImageOps.Rgb {
        val n = TfliteEmbeddingSignal.INPUT
        val cx0 = x0.coerceIn(0, src.width - 1); val cy0 = y0.coerceIn(0, src.height - 1)
        val cx1 = x1.coerceIn(cx0 + 1, src.width); val cy1 = y1.coerceIn(cy0 + 1, src.height)
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(cx0, cy0, cx1, cy1), Rect(0, 0, n, n), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(n * n)
        out.getPixels(px, 0, n, 0, 0, n, n)
        out.recycle()
        val rgb = ByteArray(n * n * 3)
        var o = 0
        for (c in px) { rgb[o++] = (c shr 16 and 0xff).toByte(); rgb[o++] = (c shr 8 and 0xff).toByte(); rgb[o++] = (c and 0xff).toByte() }
        return FrameImageOps.Rgb(rgb, n, n)
    }

    fun close() {
        runCatching { landmarker?.close() }; landmarker = null
        runCatching { embedder?.close() }; embedder = null
    }
}
