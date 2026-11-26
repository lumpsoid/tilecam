package dev.tilecam

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.tilecam.camera.LensChoice
import dev.tilecam.camera.LensSelector
import dev.tilecam.camera.TileCamera
import dev.tilecam.capture.GridPlan
import dev.tilecam.capture.Shot
import dev.tilecam.capture.ShootSession
import dev.tilecam.capture.TileState
import dev.tilecam.capture.TileTarget
import dev.tilecam.sensors.OrientationTracker
import dev.tilecam.sensors.Pose
import dev.tilecam.sensors.wrapDegrees
import dev.tilecam.stitch.Stitcher
import dev.tilecam.ui.AspectFrameLayout
import dev.tilecam.ui.GuidanceOverlay
import org.opencv.android.OpenCVLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private enum class Mode { SETUP, CORNER_A, SHOOTING, STITCHING }

    private lateinit var frame: AspectFrameLayout
    private lateinit var preview: SurfaceView
    private lateinit var overlay: GuidanceOverlay
    private lateinit var status: TextView
    private lateinit var primary: Button
    private lateinit var secondary: Button

    private lateinit var tracker: OrientationTracker
    private var choice: LensChoice? = null
    private var camera: TileCamera? = null
    private var cameraOpen = false
    private var surfaceMatches = false

    /** Feature extraction, overlap checks and stitching, one job at a time. */
    private val worker = Executors.newSingleThreadExecutor()
    /** Only touched on [worker]. */
    private val features = HashMap<Int, Stitcher.Features>()

    private var mode = Mode.SETUP
    private var cornerA: Pose? = null
    private var session: ShootSession? = null
    private var stableSince = 0L
    private var capturing = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setUpCamera() else status.text = "TileCam needs the camera permission."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        frame = findViewById(R.id.preview_frame)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        primary = findViewById(R.id.primary)
        secondary = findViewById(R.id.secondary)

        check(OpenCVLoader.initLocal()) { "OpenCV failed to load" }

        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        tracker = OrientationTracker(this, rotation, ::onPose)
        if (!tracker.available) status.text = "This phone has no rotation sensor; guidance is unavailable."

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val size = camera?.previewSize ?: return
                surfaceMatches = width == size.width && height == size.height
                openCameraIfReady()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceMatches = false
                closeCamera()
            }
        })

        primary.setOnClickListener { onPrimary() }
        secondary.setOnClickListener { restart() }
        render()

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) setUpCamera()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        tracker.start()
        openCameraIfReady()
    }

    override fun onPause() {
        tracker.stop()
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        camera?.release()
        worker.shutdown()
        super.onDestroy()
    }

    private fun setUpCamera() {
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
        val picked = LensSelector.pick(LensSelector.backLenses(manager)) ?: run {
            status.text = "No back camera found."
            return
        }
        choice = picked
        val cam = TileCamera(this, picked, rotation)
        camera = cam
        overlay.hFov = picked.hFovDeg
        overlay.vFov = picked.vFovDeg
        frame.setAspectRatio(cam.previewSize.width, cam.previewSize.height)
        preview.holder.setFixedSize(cam.previewSize.width, cam.previewSize.height)
        render()
    }

    private fun openCameraIfReady() {
        val cam = camera ?: return
        if (cameraOpen || !surfaceMatches || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        cameraOpen = true
        cam.open(preview.holder.surface) { msg -> runOnUiThread { status.text = msg } }
    }

    private fun closeCamera() {
        if (!cameraOpen) return
        cameraOpen = false
        camera?.close()
    }

    // --- Flow -------------------------------------------------------------------------------------

    private fun onPrimary() {
        val cam = camera ?: return
        val c = choice ?: return
        when (mode) {
            Mode.SETUP -> {
                cam.lock3A()
                cornerA = tracker.setReference() ?: return
                mode = Mode.CORNER_A
            }
            Mode.CORNER_A -> {
                val a = cornerA ?: return
                val b = tracker.latest ?: return
                val plan = GridPlan.between(a, b, c.hFovDeg, c.vFovDeg)
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                session = ShootSession(File(filesDir, "sessions/$stamp"), plan, c.hFovDeg, c.vFovDeg)
                worker.execute { features.values.forEach { it.release() }; features.clear() }
                mode = Mode.SHOOTING
            }
            Mode.SHOOTING -> stitch()
            Mode.STITCHING -> Unit
        }
        render()
    }

    private fun restart() {
        if (mode == Mode.STITCHING) return
        camera?.unlock3A()
        cornerA = null
        session = null
        mode = Mode.SETUP
        stableSince = 0L
        render()
    }

    private fun render() {
        overlay.cornerA = cornerA
        overlay.session = session
        if (mode != Mode.SHOOTING) {
            overlay.target = null
            overlay.hold = 0f
        }
        val lens = choice?.let {
            val zoom = if (it.zoom > 1f) ", %.1f× crop".format(it.zoom) else ""
            " (lens ≈ %.0f mm$zoom)".format(it.lens.equivalentMm)
        } ?: ""
        primary.isEnabled = camera != null && mode != Mode.STITCHING &&
            (mode != Mode.SHOOTING || (session?.captured()?.size ?: 0) >= 2)
        secondary.isEnabled = mode == Mode.CORNER_A || mode == Mode.SHOOTING
        primary.text = when (mode) {
            Mode.SETUP -> "Lock & set first corner"
            Mode.CORNER_A -> "Set opposite corner"
            Mode.SHOOTING -> "Stitch"
            Mode.STITCHING -> "Stitching…"
        }
        secondary.text = "Restart"
        if (mode == Mode.SETUP) {
            status.text = "Aim the crosshair at one corner of the scene, then tap Lock$lens"
        }
        overlay.invalidate()
    }

    // --- Guidance ---------------------------------------------------------------------------------

    private fun onPose(p: Pose) {
        overlay.pose = p
        when (mode) {
            Mode.CORNER_A -> {
                val c = choice
                val a = cornerA
                if (c != null && a != null) {
                    val plan = GridPlan.between(a, p, c.hFovDeg, c.vFovDeg)
                    status.text = "Aim at the opposite corner: ${plan.cols} × ${plan.rows} = ${plan.size} tiles"
                }
            }
            Mode.SHOOTING -> guide(p)
            else -> Unit
        }
        overlay.invalidate()
    }

    private fun guide(p: Pose) {
        val s = session ?: return
        val c = choice ?: return
        val t = s.next()
        overlay.target = t
        if (t == null) {
            overlay.hold = 0f
            stableSince = 0L
            val weak = s.states.count { it == TileState.WEAK }
            status.text = if (weak == 0) "All ${s.plan.size} tiles captured. Tap Stitch."
            else "All tiles captured; $weak have weak overlap and will be placed by the gyro. Tap Stitch."
            return
        }

        val dYaw = wrapDegrees(t.yaw - p.yaw) * cos(Math.toRadians(p.pitch))
        val dPitch = t.pitch - p.pitch
        val tolerance = max(1.0, c.hFovDeg * 0.04)
        val aligned = abs(dYaw) < tolerance && abs(dPitch) < tolerance
        val level = abs(p.roll) < GuidanceOverlay.LEVEL_TOLERANCE
        val steady = p.speed < STEADY_DEG_PER_S
        val done = s.captured().size
        val retake = if (s.states[t.index] == TileState.WEAK) " (retake: not enough overlap)" else ""

        status.text = when {
            capturing -> "Capturing…"
            p.speed > FAST_DEG_PER_S -> "Slow down"
            !level -> "Keep the phone level"
            aligned && !steady -> "Hold still…"
            aligned -> "Hold…"
            else -> "Tile ${done + 1}/${s.plan.size}$retake: move the crosshair to the dot. Turn the phone in place; don't slide it."
        }

        if (aligned && level && steady && !capturing) {
            val now = SystemClock.elapsedRealtime()
            if (stableSince == 0L) stableSince = now
            overlay.hold = ((now - stableSince) / HOLD_MS.toFloat()).coerceAtMost(1f)
            if (overlay.hold >= 1f) capture(s, t, p)
        } else {
            stableSince = 0L
            overlay.hold = 0f
        }
    }

    private fun capture(s: ShootSession, t: TileTarget, p: Pose) {
        val cam = camera ?: return
        capturing = true
        overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val file = s.fileFor(t)
        val size = cam.outputSize
        cam.capture { jpeg ->
            s.writeJpeg(file, jpeg)
            runOnUiThread {
                capturing = false
                stableSince = 0L
                overlay.hold = 0f
                if (session !== s) return@runOnUiThread
                val shot = s.record(t, file, p, size.width, size.height)
                checkOverlap(s, shot)
                render()
            }
        }
    }

    /** Confirms the new tile shares enough features with its neighbours to be stitched; otherwise asks for a retake. */
    private fun checkOverlap(s: ShootSession, shot: Shot) {
        val neighbours = s.capturedNeighbours(shot)
        worker.execute {
            val ok = try {
                features.remove(shot.index)?.release()
                val f = Stitcher.detect(shot.file).also { features[shot.index] = it }
                neighbours.isEmpty() || neighbours.any { n ->
                    features[n.index]?.let { Stitcher.match(it, f) } != null
                }
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (session === s) {
                    s.setState(shot.index, if (ok) TileState.OK else TileState.WEAK)
                    overlay.invalidate()
                }
            }
        }
    }

    // --- Stitching --------------------------------------------------------------------------------

    private fun stitch() {
        val s = session ?: return
        val shots = s.captured()
        mode = Mode.STITCHING
        render()
        worker.execute {
            try {
                val result = Stitcher.stitch(shots, s.hFov, File(s.dir, "stitched.jpg")) { msg ->
                    runOnUiThread { status.text = msg }
                }
                val uri = saveToGallery(result.file)
                runOnUiThread {
                    val note = if (result.gyroPlaced > 0) " (${result.gyroPlaced} tiles placed by gyro only)" else ""
                    status.text = "Saved ${result.width} × ${result.height} to Pictures/TileCam$note"
                    view(uri)
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Stitching failed: ${e.message}" }
            } finally {
                runOnUiThread {
                    mode = Mode.SHOOTING
                    render()
                }
            }
        }
    }

    private fun saveToGallery(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "TileCam_${file.parentFile?.name}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TileCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create a gallery entry")
        contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun view(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
        }
    }

    companion object {
        private const val HOLD_MS = 400L
        private const val STEADY_DEG_PER_S = 2.5
        private const val FAST_DEG_PER_S = 30.0
    }
}
