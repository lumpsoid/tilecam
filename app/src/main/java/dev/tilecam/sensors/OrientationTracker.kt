package dev.tilecam.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Where the camera points, in degrees.
 * [yaw] is relative to the reference set by [OrientationTracker.setReference], positive to the right.
 * [pitch] is above the horizon, [roll] is positive when the right side of the screen is raised.
 * [speed] is how fast the pointing direction moves, in degrees per second.
 */
data class Pose(val yaw: Double, val pitch: Double, val roll: Double, val speed: Double)

fun wrapDegrees(a: Double): Double {
    var x = (a + 180.0) % 360.0
    if (x < 0) x += 360.0
    return x - 180.0
}

/**
 * Turns the rotation-vector sensor into a camera [Pose].
 * Uses the game rotation vector (gyro + accelerometer, no compass) so nearby metal can't make the heading jump.
 */
class OrientationTracker(context: Context, private val displayRotation: Int, private val onPose: (Pose) -> Unit) :
    SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val raw = FloatArray(9)
    private val r = FloatArray(9)
    private var heading = 0.0
    private var referenceHeading = 0.0
    private var lastDir: DoubleArray? = null
    private var lastTimestamp = 0L
    private var speed = 0.0

    var latest: Pose? = null
        private set

    val available: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensors.unregisterListener(this)

    /** Makes the current heading yaw 0. */
    fun setReference(): Pose? {
        referenceHeading = heading
        latest = latest?.copy(yaw = 0.0)
        return latest
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(raw, event.values)
        // Remap so the matrix's x axis is screen-right and y is screen-up for the current display rotation.
        val (ax, ay) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(raw, ax, ay, r)

        // The back camera looks along -z of the screen; world z is up.
        val dir = doubleArrayOf(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())
        val pitch = Math.toDegrees(asin(dir[2].coerceIn(-1.0, 1.0)))
        heading = Math.toDegrees(atan2(dir[0], dir[1]))
        val roll = Math.toDegrees(asin(r[6].toDouble().coerceIn(-1.0, 1.0)))

        lastDir?.let { prev ->
            val dt = (event.timestamp - lastTimestamp) / 1e9
            if (dt > 0) {
                val instant = Math.toDegrees(angleBetween(prev, dir)) / dt
                speed = speed * 0.8 + instant * 0.2
            }
        }
        lastDir = dir
        lastTimestamp = event.timestamp

        val pose = Pose(wrapDegrees(heading - referenceHeading), pitch, roll, speed)
        latest = pose
        onPose(pose)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun angleBetween(a: DoubleArray, b: DoubleArray): Double {
        val cx = a[1] * b[2] - a[2] * b[1]
        val cy = a[2] * b[0] - a[0] * b[2]
        val cz = a[0] * b[1] - a[1] * b[0]
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        return atan2(sqrt(cx * cx + cy * cy + cz * cz), dot)
    }
}
