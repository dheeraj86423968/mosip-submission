package io.mosip.vivid.android.bridge

import android.content.Context
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import io.flutter.view.TextureRegistry

/**
 * Routes the CameraX `Preview` use case into a Flutter texture so the Dart `LivenessOverlay`
 * can show `Texture(textureId: ...)` under the oval guide. The front camera image is mirrored
 * in Dart (Transform.scale(scaleX: -1)); analysis frames stay unmirrored.
 */
internal class FlutterPreviewSurface(
    private val context: Context,
    private val entry: TextureRegistry.SurfaceTextureEntry
) : Preview.SurfaceProvider {
    private var surface: Surface? = null

    override fun onSurfaceRequested(request: SurfaceRequest) {
        val st = entry.surfaceTexture()
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val s = Surface(st)
        surface?.release()
        surface = s
        request.provideSurface(s, ContextCompat.getMainExecutor(context)) { _ ->
            s.release()
            if (surface === s) surface = null
        }
    }

    fun release() { surface?.release(); surface = null }
}
