package dev.tilecam.sensors

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A direction or point in the tracker's world: x is right and y is forward at yaw 0, z is up. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun normalized(): Vec3 = this * (1 / sqrt(this dot this))

    /** Degrees, positive to the right; same convention as [Pose.yaw]. */
    val yaw: Double get() = Math.toDegrees(atan2(x, y))
    val pitch: Double get() = Math.toDegrees(asin((z / sqrt(this dot this)).coerceIn(-1.0, 1.0)))
}

/**
 * A pinhole camera's orientation as world-space unit vectors.
 * Image coordinates are on the plane one unit in front of the lens: x right, y up,
 * so a frame with field of view h × v spans ±tan(h/2) × ±tan(v/2).
 */
class Aim(val right: Vec3, val up: Vec3, val forward: Vec3) {

    /** The world ray through image point ([x], [y]). Straight lines in the image stay straight in the world. */
    fun ray(x: Double, y: Double): Vec3 = forward + right * x + up * y

    /** Image coordinates of the world ray [d], or null when it points behind the camera. */
    fun toImage(d: Vec3): Pair<Double, Double>? {
        val z = d dot forward
        if (z <= 1e-6) return null
        return (d dot right) / z to (d dot up) / z
    }

    companion object {
        /** Camera at [yaw] and [pitch], turned [roll] degrees about its own axis (right side up when positive). */
        fun of(yaw: Double, pitch: Double, roll: Double = 0.0): Aim {
            val y = Math.toRadians(yaw)
            val p = Math.toRadians(pitch)
            val r = Math.toRadians(roll)
            val forward = Vec3(cos(p) * sin(y), cos(p) * cos(y), sin(p))
            val levelRight = Vec3(cos(y), -sin(y), 0.0)
            val levelUp = Vec3(-sin(p) * sin(y), -sin(p) * cos(y), cos(p))
            return Aim(levelRight * cos(r) + levelUp * sin(r), levelUp * cos(r) + levelRight * -sin(r), forward)
        }

        fun of(p: Pose) = of(p.yaw, p.pitch, p.roll)
    }
}
