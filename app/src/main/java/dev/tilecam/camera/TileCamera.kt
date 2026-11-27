package dev.tilecam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Camera2 wrapper for tile shooting: a preview stream, a full-resolution JPEG stream,
 * and a 3A lock so every tile has the same exposure, white balance and focus.
 */
class TileCamera(context: Context, val choice: LensChoice, displayRotation: Int) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("tile-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val lens = choice.lens

    private val jpegOrientation: Int
    val captureSize: Size
    val previewSize: Size

    /** Size of saved tiles after orientation is applied. */
    val outputSize: Size get() =
        if (jpegOrientation % 180 == 0) captureSize else Size(captureSize.height, captureSize.width)

    private val reader: ImageReader
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewRequest: CaptureRequest.Builder? = null
    private var pendingJpeg: ((ByteArray) -> Unit)? = null

    /** True once [lock3A] ran; survives a close/open so a resumed session keeps the lock. */
    var locked = false
        private set

    init {
        val chars = manager.getCameraCharacteristics(lens.id)
        val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
        val aspect = lens.activeArray.width().toFloat() / lens.activeArray.height()
        fun matchesAspect(s: Size) = abs(s.width.toFloat() / s.height - aspect) < 0.02f
        captureSize = map.getOutputSizes(ImageFormat.JPEG).filter(::matchesAspect).maxBy { it.width * it.height }
        previewSize = map.getOutputSizes(SurfaceHolder::class.java)
            .filter { matchesAspect(it) && it.width <= 1920 && it.height <= 1440 }
            .maxBy { it.width * it.height }

        val sensorOrientation = manager.getCameraCharacteristics(lens.logicalId)[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
        val deviceDegrees = (360 - displayRotation * 90) % 360
        jpegOrientation = (sensorOrientation + deviceDegrees) % 360

        reader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r ->
                r.acquireNextImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                    pendingJpeg?.invoke(bytes)
                    pendingJpeg = null
                }
            }, handler)
        }
    }

    @SuppressLint("MissingPermission")
    fun open(preview: Surface, onError: (String) -> Unit) {
        manager.openCamera(lens.logicalId, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera, preview, onError)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                device = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                device = null
                onError("Camera error $error")
            }
        })
    }

    private fun createSession(camera: CameraDevice, preview: Surface, onError: (String) -> Unit) {
        val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(reader.surface))
        lens.physicalId?.let { id -> outputs.forEach { it.setPhysicalCameraId(id) } }
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview)
                        applyCommon(this)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    if (locked) lock3A() else s.setRepeatingRequest(previewRequest!!.build(), null, handler)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) = onError("Could not configure the camera")
            })
        camera.createCaptureSession(config)
    }

    /** Settings shared by preview and still requests, so both see the same field of view. */
    private fun applyCommon(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        // Electronic stabilisation crops and warps frames, which would change the field of view per tile.
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (choice.zoomByRatio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, choice.zoom)
        } else if (choice.zoom > 1f) {
            b.set(CaptureRequest.SCALER_CROP_REGION, cropRegion())
        }
    }

    private fun cropRegion(): Rect {
        val a = lens.activeArray
        val w = (a.width() / choice.zoom).toInt()
        val h = (a.height() / choice.zoom).toInt()
        val left = a.left + (a.width() - w) / 2
        val top = a.top + (a.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /** Focuses once, then freezes focus, exposure and white balance for the rest of the session. */
    fun lock3A() = handler.post {
        locked = true
        val s = session ?: return@post
        val b = previewRequest ?: return@post
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        s.capture(b.build(), null, handler)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        b.set(CaptureRequest.CONTROL_AE_LOCK, true)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        s.setRepeatingRequest(b.build(), null, handler)
    }

    fun unlock3A() = handler.post {
        locked = false
        val s = session ?: return@post
        val b = previewRequest ?: return@post
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        s.capture(b.build(), null, handler)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        b.set(CaptureRequest.CONTROL_AE_LOCK, false)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        s.setRepeatingRequest(b.build(), null, handler)
    }

    /** Takes one still; [onJpeg] runs on the camera thread. */
    fun capture(onJpeg: (ByteArray) -> Unit) = handler.post {
        val s = session ?: return@post
        val d = device ?: return@post
        pendingJpeg = onJpeg
        val request = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            applyCommon(this)
            set(CaptureRequest.CONTROL_AF_MODE,
                if (locked) CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_LOCK, locked)
            set(CaptureRequest.CONTROL_AWB_LOCK, locked)
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            set(CaptureRequest.JPEG_QUALITY, 95.toByte())
        }
        s.capture(request.build(), null, handler)
    }

    fun close() = handler.post {
        session?.close()
        session = null
        device?.close()
        device = null
    }

    fun release() {
        close()
        handler.post {
            reader.close()
            thread.quitSafely()
        }
    }
}
