package dev.tilecam.capture

import dev.tilecam.sensors.Aim
import dev.tilecam.sensors.Pose
import dev.tilecam.sensors.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Where the camera should point for one tile, in degrees. */
data class TileTarget(val index: Int, val row: Int, val col: Int, val yaw: Double, val pitch: Double)

/**
 * A grid of overlapping tiles covering the scene between two corners.
 *
 * The scene is a rectangle on a flat plane (upright near eye level, otherwise facing the scene),
 * so a poster or a wall keeps straight edges; the grid splits it into equal cells. Each tile is checked through
 * its real pinhole frame, so edges and corners stay covered even away from the horizon.
 * Row 0 is the top row; targets are ordered in a serpentine, so each tile overlaps the one shot before it.
 */
class GridPlan private constructor(
    private val plane: Aim,
    /** Scene rectangle on [plane]: left edge, top edge, cell size. y is up, so rows go down from [top]. */
    private val left: Double,
    private val top: Double,
    private val cellW: Double,
    private val cellH: Double,
    val cols: Int,
    val rows: Int,
) {
    val size: Int get() = rows * cols

    val targets: List<TileTarget> = buildList {
        for (row in 0 until rows) {
            val order = if (row % 2 == 0) 0 until cols else (0 until cols).reversed()
            for (col in order) {
                val d = plane.ray(left + (col + 0.5) * cellW, top - (row + 0.5) * cellH)
                add(TileTarget(row * cols + col, row, col, d.yaw, d.pitch))
            }
        }
    }

    /** The scene's corners as world rays, clockwise from top-left. */
    val outline: List<Vec3> get() {
        val right = left + cols * cellW
        val bottom = top - rows * cellH
        return listOf(plane.ray(left, top), plane.ray(right, top), plane.ray(right, bottom), plane.ray(left, bottom))
    }

    /** Where [p] points in grid units (column, row; cell centres are whole numbers), or null when facing away. */
    fun gridPosition(p: Pose): Pair<Double, Double>? {
        val (x, y) = plane.toImage(Aim.of(p).forward) ?: return null
        return (x - left) / cellW - 0.5 to (top - y) / cellH - 0.5
    }

    companion object {
        const val OVERLAP = 0.30
        /** Largest roll, in degrees, that still counts as level for a capture. */
        const val LEVEL_TOLERANCE = 3.0
        private const val MAX_SIDE = 30
        /** Scenes centred within this many degrees of the horizon are planned as upright. */
        private const val UPRIGHT_PITCH = 45.0

        /** How far, in degrees, the crosshair may be from a tile's centre when it is captured. */
        fun aimTolerance(hFov: Double) = max(1.0, hFov * 0.04)

        /** [a] and [b] are where the crosshair pointed at two opposite corners of the scene. */
        fun between(a: Pose, b: Pose, hFov: Double, vFov: Double, overlap: Double = OVERLAP): GridPlan {
            val da = Aim.of(a.yaw, a.pitch).forward
            val db = Aim.of(b.yaw, b.pitch).forward
            val centre = (da + db).normalized()
            // Near eye level the scene is usually upright (a wall, a poster, a landscape), so plan on an upright
            // plane: its vertical edges stay vertical there, and the two corners pin down the other two.
            // Steeper than that, e.g. a puzzle on a table, the plane faces the scene's centre instead.
            val upright = Aim.of(centre.yaw, 0.0)
            val facing = Aim.of(centre.yaw, centre.pitch)
            val plane = if (abs(centre.pitch) < UPRIGHT_PITCH && upright.toImage(da) != null &&
                upright.toImage(db) != null) upright else facing
            // Both corners are less than 90° from their midpoint, so they always land on the facing plane.
            val (xa, ya) = plane.toImage(da) ?: (0.0 to 0.0)
            val (xb, yb) = plane.toImage(db) ?: (0.0 to 0.0)
            val w = abs(xb - xa)
            val h = abs(yb - ya)

            // A tile must cover its cell even when the shot is a little off target or tilted, so plan with a smaller frame.
            val slack = aimTolerance(hFov)
            val halfW = tan(Math.toRadians(hFov / 2 - slack))
            val halfH = tan(Math.toRadians(vFov / 2 - slack))
            val tilt = sin(Math.toRadians(LEVEL_TOLERANCE))

            var cols = 1
            var rows = 1
            while (true) {
                val cw = w / cols
                val ch = h / rows
                // Each tile reaches this far past its cell into its neighbours, so neighbours share [overlap] of a frame.
                val mx = cw * overlap / (2 * (1 - overlap))
                val my = ch * overlap / (2 * (1 - overlap))
                var tooNarrow = false
                var tooShort = false
                for (row in 0 until rows) for (col in 0 until cols) {
                    val x0 = min(xa, xb) + col * cw
                    val y1 = max(ya, yb) - row * ch
                    val tile = plane.ray(x0 + cw / 2, y1 - ch / 2).let { Aim.of(it.yaw, it.pitch) }
                    val xs = doubleArrayOf(x0 - if (col > 0) mx else 0.0, x0 + cw + if (col < cols - 1) mx else 0.0)
                    val ys = doubleArrayOf(y1 + if (row > 0) my else 0.0, y1 - ch - if (row < rows - 1) my else 0.0)
                    for (x in xs) for (y in ys) {
                        val uv = tile.toImage(plane.ray(x, y))
                        if (uv == null) {
                            tooNarrow = true
                            tooShort = true
                            continue
                        }
                        val (u, v) = uv
                        // A roll of up to [LEVEL_TOLERANCE] swings a corner sideways by about this much.
                        if (abs(u) + abs(v) * tilt > halfW) tooNarrow = true
                        if (abs(v) + abs(u) * tilt > halfH) tooShort = true
                    }
                }
                val wider = tooNarrow && cols < MAX_SIDE
                val taller = tooShort && rows < MAX_SIDE
                if (!wider && !taller) break
                if (wider) cols++
                if (taller) rows++
            }
            return GridPlan(plane, min(xa, xb), max(ya, yb), w / cols, h / rows, cols, rows)
        }
    }
}
