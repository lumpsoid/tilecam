package dev.tilecam.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.SizeF
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot

/** One physical lens on the back of the phone. */
data class Lens(
    /** Camera id to open. */
    val logicalId: String,
    /** Set when the lens is only reachable as a sub-camera of a logical multi-camera. */
    val physicalId: String?,
    val focalMm: Float,
    val sensorSize: SizeF,
    val activeArray: Rect,
    /** Highest CONTROL_ZOOM_RATIO the camera accepts; 1 when it has none. */
    val maxZoomRatio: Float,
) {
    val id: String get() = physicalId ?: logicalId

    /** 35 mm-equivalent focal length. */
    val equivalentMm: Float get() = focalMm * FULL_FRAME_DIAGONAL / hypot(sensorSize.width, sensorSize.height)

    companion object {
        const val FULL_FRAME_DIAGONAL = 43.27f
    }
}

/** The lens to shoot with, plus the zoom needed to reach the target focal length. */
data class LensChoice(val lens: Lens, val zoom: Float) {
    val equivalentMm: Float get() = lens.equivalentMm * zoom

    /**
     * Zoom through CONTROL_ZOOM_RATIO rather than a crop. On a multi-lens camera this lets the phone switch to a
     * telephoto it doesn't list for apps; a crop always stays on the wide lens. The field of view is the same either way.
     */
    val zoomByRatio: Boolean get() = zoom > 1f && lens.maxZoomRatio >= zoom

    /** Field of view of the output image in landscape, in degrees. */
    val hFovDeg: Double get() = fov(lens.sensorSize.width)
    val vFovDeg: Double get() = fov(lens.sensorSize.height)

    private fun fov(sensorMm: Float) =
        Math.toDegrees(2 * atan(sensorMm / (2.0 * lens.focalMm * zoom)))
}

object LensSelector {

    fun backLenses(manager: CameraManager): List<Lens> = buildList {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK) continue
            lensOf(id, null, chars)?.let(::add)
            for (physicalId in chars.physicalCameraIds) {
                lensOf(id, physicalId, manager.getCameraCharacteristics(physicalId))?.let(::add)
            }
        }
    }

    /**
     * Prefers a listed lens at or slightly below [targetMm] (a longer one is fine: more tiles, more detail).
     * Many phones hide their telephoto from apps; then the camera that zooms furthest is zoomed by ratio,
     * so the phone can switch to that telephoto itself. Without zoom-ratio support (Android 10), falls back
     * to cropping the longest lens, which only adds detail on high-res sensors.
     */
    fun pick(lenses: List<Lens>, targetMm: Float = 50f): LensChoice? {
        if (lenses.isEmpty()) return null
        // A standalone camera is simpler to drive than a physical sub-camera, so it wins ties.
        val ordered = lenses.sortedWith(compareBy<Lens> { it.equivalentMm }.thenBy { it.physicalId != null })
        val optical = ordered.firstOrNull { it.equivalentMm >= targetMm * 0.9f }
        if (optical != null) return LensChoice(optical, 1f)
        val zooming = ordered.filter { it.physicalId == null && it.maxZoomRatio >= targetMm / it.equivalentMm }
            .maxByOrNull { it.maxZoomRatio }
        if (zooming != null) return LensChoice(zooming, targetMm / zooming.equivalentMm)
        val longest = ordered.last().equivalentMm
        val base = ordered.filter { abs(it.equivalentMm - longest) < 1f }.minBy { if (it.physicalId == null) 0 else 1 }
        return LensChoice(base, targetMm / base.equivalentMm)
    }

    private fun lensOf(logicalId: String, physicalId: String?, chars: CameraCharacteristics): Lens? {
        val focal = chars[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: return null
        val sensor = chars[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return null
        val active = chars[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return null
        val maxZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.upper ?: 1f
        } else 1f
        return Lens(logicalId, physicalId, focal, sensor, active, maxZoom)
    }
}
