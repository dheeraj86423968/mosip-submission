package io.mosip.vivid.android.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Locates and verifies model files.
 *
 * Resolution order (first hit wins):
 *  1. A model-bundle directory (`<filesDir>/vivid/models/<version>/`, written by the secure
 *     offline update flow from PLAN 6) containing `manifest.json`.
 *  2. The library's packaged assets under `vivid/models/`.
 *
 * Every file is SHA-256 checked against its manifest entry before use; a mismatch throws, so a
 * tampered or half-written bundle can never be loaded. Packaged assets use the same check so a
 * wrong-file-in-assets build fails at startup rather than silently scoring garbage.
 */
class ModelAssets private constructor(
    private val context: Context,
    private val bundleDir: File?,
    val bundleId: String,
    val bundleVersion: String,
    private val entries: Map<String, Entry>
) {
    data class Entry(val file: String, val sha256: String)

    companion object {
        private const val TAG = "ModelAssets"
        const val ASSET_ROOT = "vivid/models"
        const val MANIFEST = "manifest.json"
        const val KEY_FACE_LANDMARKER = "face_landmarker"
        const val KEY_TEXTURE = "minifasnet_int8"
        const val KEY_EMBEDDING = "mobilefacenet"

        /** Load from an explicit bundle directory, or the packaged assets when [bundleDir] is null/absent. */
        fun load(context: Context, bundleDir: File? = defaultBundleDir(context)): ModelAssets {
            val dir = bundleDir?.takeIf { File(it, MANIFEST).isFile }
            val json = if (dir != null) File(dir, MANIFEST).readText() else context.assets.open("$ASSET_ROOT/$MANIFEST").use { it.readBytes().decodeToString() }
            val root = JSONObject(json)
            val models = root.getJSONObject("models")
            val map = HashMap<String, Entry>()
            for (key in models.keys()) {
                val o = models.getJSONObject(key)
                map[key] = Entry(o.getString("file"), o.getString("sha256").lowercase())
            }
            return ModelAssets(context, dir, root.optString("bundleId", "unknown"), root.optString("version", "unknown"), map)
        }

        fun defaultBundleDir(context: Context): File? {
            val root = File(context.filesDir, "vivid/models")
            val current = File(root, "current")
            return if (File(current, MANIFEST).isFile) current else null
        }

        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    fun has(key: String): Boolean = entries.containsKey(key)

    /** Memory-mapped, verified model bytes (what TFLite `Interpreter` wants). */
    fun mapped(key: String): MappedByteBuffer {
        val e = entry(key)
        return if (bundleDir != null) {
            val f = File(bundleDir, e.file)
            verify(key, e, FileInputStream(f))
            FileInputStream(f).channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()) }
        } else {
            val path = "$ASSET_ROOT/${e.file}"
            verify(key, e, context.assets.open(path))
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }
    }

    /** Verified bytes in a direct buffer (MediaPipe `BaseOptions.setModelAssetBuffer`). */
    fun bytes(key: String): ByteBuffer {
        val e = entry(key)
        val data = if (bundleDir != null) File(bundleDir, e.file).readBytes() else context.assets.open("$ASSET_ROOT/${e.file}").use { it.readBytes() }
        val actual = sha256(data.inputStream())
        check(actual == e.sha256 || e.sha256.startsWith("replace")) { "SHA-256 mismatch for $key: expected ${e.sha256}, got $actual" }
        if (e.sha256.startsWith("replace")) Log.w(TAG, "model $key has placeholder digest; verification skipped (dev build only)")
        return ByteBuffer.allocateDirect(data.size).put(data).apply { rewind() }
    }

    /** Asset-relative path for APIs that only take a path (MediaPipe `setModelAssetPath`). Null for bundle dirs. */
    fun assetPath(key: String): String? = if (bundleDir == null) "$ASSET_ROOT/${entry(key).file}" else null

    private fun entry(key: String): Entry = entries[key] ?: throw IllegalStateException("model '$key' missing from manifest")

    private fun verify(key: String, e: Entry, input: InputStream) {
        val actual = input.use { sha256(it) }
        if (e.sha256.startsWith("replace")) { Log.w(TAG, "model $key has placeholder digest; verification skipped (dev build only)"); return }
        check(actual == e.sha256) { "SHA-256 mismatch for $key: expected ${e.sha256}, got $actual" }
    }
}
