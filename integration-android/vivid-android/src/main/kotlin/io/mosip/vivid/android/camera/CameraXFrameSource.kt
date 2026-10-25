package io.mosip.vivid.android.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.mosip.vivid.android.perf.FrameSkipper
import io.mosip.vivid.android.perf.ThermalGovernor
import io.mosip.vivid.device.DeviceCapabilities
import io.mosip.vivid.device.DeviceException
import io.mosip.vivid.device.DeviceHealth
import io.mosip.vivid.device.FaceFrame
import io.mosip.vivid.device.FaceFrameSource
import io.mosip.vivid.device.FrameSink
import io.mosip.vivid.device.PixelFormat
import io.mosip.vivid.device.StreamOptions
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * [FaceFrameSource] backed by CameraX `ImageAnalysis` (PLAN 3.2 option 1).
 *
 * - `STRATEGY_KEEP_ONLY_LATEST` gives camera-side backpressure; [FrameSkipper] adds engine-side.
 * - Frames are delivered as NV21, rotated upright using `imageInfo.rotationDegrees`, unmirrored
 *   (the Flutter overlay mirrors the preview; mirroring the analysis frame would flip yaw).
 * - [DeviceHealth] follows CameraX `CameraState`: CONNECTING while binding, CONNECTED on first
 *   frame, DEGRADED on thermal throttling / stalls, RECONNECTING while rebinding, FAILED on
 *   unrecoverable camera errors, DISCONNECTED after [stop].
 * - [ThermalGovernor] lowers the delivered fps when the OS reports throttling.
 *
 * Must be constructed on any thread; [start]/[stop] hop to the main thread internally because
 * CameraX binding is main-thread only. Frames are delivered on a dedicated single-thread executor.
 */
class CameraXFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    private val targetResolution: Size = Size(640, 480),
    private val thermalGovernor: ThermalGovernor? = null
) : FaceFrameSource {

    companion object {
        const val ADAPTER_ID = "android-camerax"
        private const val TAG = "CameraXFrameSource"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vivid-camera-analysis").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val converter = YuvToNv21()
    private val skipper = FrameSkipper()
    private val health = AtomicReference(DeviceHealth.disconnected("not started"))
    private val sequence = AtomicLong(0)
    private val lastFrameWallNanos = AtomicLong(0)
    private val running = AtomicBoolean(false)
    private val ended = AtomicBoolean(true)

    private var sink: FrameSink? = null
    private var options: StreamOptions = StreamOptions.defaults()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private var reconnects = 0
    private var actualWidth = targetResolution.width
    private var actualHeight = targetResolution.height

    private val thermalListener = ThermalGovernor.Listener { level ->
        skipper.targetFps = minOf(options.targetFps(), level.fps)
        if (level != ThermalGovernor.Level.NORMAL) setHealth(DeviceHealth.degraded("thermal:" + level.name))
        else if (health.get().state() == DeviceHealth.State.DEGRADED) setHealth(DeviceHealth.connected())
    }

    private val stallWatchdog = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val last = lastFrameWallNanos.get()
            val stalled = last != 0L && (System.nanoTime() - last) / 1_000_000 > options.stallTimeoutMillis()
            if (stalled) {
                if (reconnects < options.reconnectAttempts()) {
                    reconnects++
                    setHealth(DeviceHealth.reconnecting("no frame for ${options.stallTimeoutMillis()} ms (attempt $reconnects)"))
                    rebind()
                } else {
                    fail("camera stalled; reconnect attempts exhausted")
                    return
                }
            }
            mainHandler.postDelayed(this, options.stallTimeoutMillis() / 2)
        }
    }

    /** Optional: route the CameraX Preview use case to a surface (e.g. a Flutter texture). */
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider?) {
        previewSurfaceProvider = surfaceProvider
        if (running.get()) mainHandler.post { rebind() }
    }

    override fun adapterId(): String = ADAPTER_ID

    override fun capabilities(): DeviceCapabilities = DeviceCapabilities.builder()
        .vendor("android-camerax")
        .model(android.os.Build.MODEL ?: "unknown")
        .deviceId("camerax-lens-$lensFacing")
        .sbiSpecVersion("n/a")
        .certificationLevel(DeviceCapabilities.CertificationLevel.L0)
        .resolution(actualWidth, actualHeight)
        .nominalFps(skipper.targetFps.toDouble())
        .formats(setOf(PixelFormat.NV21))
        .hasDepth(false)
        .hasIr(false)
        .signsFrames(false)
        .build()

    override fun start(sink: FrameSink, options: StreamOptions) {
        if (!running.compareAndSet(false, true)) return
        this.sink = sink
        this.options = options
        this.ended.set(false)
        reconnects = 0
        sequence.set(0)
        lastFrameWallNanos.set(0)
        skipper.reset()
        skipper.targetFps = options.targetFps()
        thermalGovernor?.let { it.addListener(thermalListener); it.start(analysisExecutor) }
        setHealth(DeviceHealth.connecting())
        mainHandler.post {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    provider = future.get()
                    bind()
                    mainHandler.postDelayed(stallWatchdog, options.stallTimeoutMillis())
                } catch (e: Exception) {
                    fail("camera provider unavailable: ${e.message}", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        thermalGovernor?.let { it.removeListener(thermalListener) }
        mainHandler.removeCallbacks(stallWatchdog)
        mainHandler.post {
            try { provider?.unbindAll() } catch (e: Exception) { Log.w(TAG, "unbindAll failed", e) }
            camera = null; analysis = null; preview = null
        }
        setHealth(DeviceHealth.disconnected("stopped"))
        end()
    }

    override fun health(): DeviceHealth = health.get()

    override fun close() {
        stop()
        analysisExecutor.shutdown()
    }

    // ---- internals -------------------------------------------------------------------------

    private fun bind() {
        val p = provider ?: throw DeviceException(DeviceException.Kind.UNAVAILABLE, "no camera provider")
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!p.hasCamera(selector)) {
            // Fall back to any camera: tablets in kiosks sometimes only have a rear module.
            val any = CameraSelector.Builder().build()
            if (!p.hasCamera(any)) throw DeviceException(DeviceException.Kind.CAMERA_UNAVAILABLE, "no camera on device")
        }
        val ia = ImageAnalysis.Builder()
            .setTargetResolution(targetResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        ia.setAnalyzer(analysisExecutor) { image -> onImage(image) }
        analysis = ia

        val useCases = mutableListOf<androidx.camera.core.UseCase>(ia)
        previewSurfaceProvider?.let { sp ->
            val pv = Preview.Builder().setTargetResolution(targetResolution).build()
            pv.setSurfaceProvider(sp)
            preview = pv
            useCases += pv
        }
        p.unbindAll()
        val cam = p.bindToLifecycle(lifecycleOwner, if (p.hasCamera(selector)) selector else CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
        camera = cam
        cam.cameraInfo.cameraState.observe(lifecycleOwner) { state -> onCameraState(state) }
    }

    private fun rebind() {
        mainHandler.post {
            if (!running.get()) return@post
            try { bind() } catch (e: Exception) { fail("rebind failed: ${e.message}", e) }
        }
    }

    private fun onCameraState(state: CameraState) {
        val err = state.error
        if (err != null) {
            when (err.code) {
                CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE, CameraState.ERROR_OTHER_RECOVERABLE_ERROR ->
                    setHealth(DeviceHealth.degraded("camera error ${err.code}"))
                CameraState.ERROR_STREAM_CONFIG, CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_CAMERA_FATAL_ERROR, CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED ->
                    fail("camera error ${err.code}")
                else -> setHealth(DeviceHealth.degraded("camera error ${err.code}"))
            }
            return
        }
        when (state.type) {
            CameraState.Type.OPEN -> if (health.get().state() == DeviceHealth.State.RECONNECTING) setHealth(DeviceHealth.connected())
            CameraState.Type.CLOSED, CameraState.Type.CLOSING -> if (running.get()) setHealth(DeviceHealth.reconnecting("camera closed"))
            else -> Unit
        }
    }

    private fun onImage(image: ImageProxy) {
        try {
            if (!running.get()) return
            val ts = image.imageInfo.timestamp
            lastFrameWallNanos.set(System.nanoTime())
            if (!skipper.shouldProcess(ts)) return
            val t0 = System.nanoTime()
            val rotation = image.imageInfo.rotationDegrees
            val r = converter.convert(image, rotation)
            actualWidth = r.width; actualHeight = r.height
            if (health.get().state() == DeviceHealth.State.CONNECTING || health.get().state() == DeviceHealth.State.RECONNECTING) {
                reconnects = 0
                setHealth(DeviceHealth.connected())
            }
            // The converter reuses its buffer; FaceFrame requires callers that keep frames to copy,
            // and FrameSink.onFrame must return quickly, so we hand out a wrap and let the engine copy
            // what it needs (the engine copies the face crop, never the whole frame).
            val frame = FaceFrame.builder()
                .sequence(sequence.incrementAndGet())
                .timestampNanos(ts)
                .width(r.width).height(r.height)
                .format(PixelFormat.NV21)
                .pixels(ByteBuffer.wrap(r.data, 0, r.width * r.height * 3 / 2))
                .build()
            sink?.onFrame(frame)
            skipper.onProcessed(System.nanoTime() - t0)
            thermalGovernor?.pollHeadroom()
        } catch (e: Exception) {
            Log.e(TAG, "frame delivery failed", e)
            sink?.onHealth(DeviceHealth.degraded("invalid frame: ${e.message}"))
        } finally {
            image.close()
        }
    }

    private fun setHealth(h: DeviceHealth) {
        val prev = health.getAndSet(h)
        if (prev.state() != h.state() || prev.reason() != h.reason()) sink?.onHealth(h)
    }

    private fun fail(reason: String, cause: Throwable? = null) {
        if (cause != null) Log.e(TAG, reason, cause) else Log.e(TAG, reason)
        setHealth(DeviceHealth.failed(reason))
        running.set(false)
        mainHandler.removeCallbacks(stallWatchdog)
        mainHandler.post { try { provider?.unbindAll() } catch (_: Exception) {} }
        end()
    }

    private fun end() {
        if (ended.compareAndSet(false, true)) sink?.onEnd()
    }
}
