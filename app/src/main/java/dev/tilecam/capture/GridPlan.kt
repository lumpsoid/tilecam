package dev.tilecam.capture

import dev.tilecam.sensors.Pose
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Where the camera should point for one tile, in degrees. */
data class TileTarget(val index: Int, val row: Int, val col: Int, val yaw: Double, val pitch: Double)

/**
 * A grid of overlapping tiles covering the angular rectangle between two corners.
 * Row 0 is the top row; targets are ordered in a serpentine, so each tile overlaps the one shot before it.
 */
class GridPlan(val colYaws: DoubleArray, val rowPitches: DoubleArray) {
    val rows: Int get() = rowPitches.size
    val cols: Int get() = colYaws.size
    val size: Int get() = rows * cols

    val targets: List<TileTarget> = buildList {
        for (row in 0 until rows) {
            val cols = if (row % 2 == 0) colYaws.indices else colYaws.indices.reversed()
            for (col in cols) add(TileTarget(row * colYaws.size + col, row, col, colYaws[col], rowPitches[row]))
        }
    }

    companion object {
        const val OVERLAP = 0.30

        /** [a] and [b] are where the crosshair pointed at two opposite corners of the scene. */
        fun between(a: Pose, b: Pose, hFov: Double, vFov: Double, overlap: Double = OVERLAP) = GridPlan(
            centres(min(a.yaw, b.yaw), max(a.yaw, b.yaw), hFov, overlap),
            centres(min(a.pitch, b.pitch), max(a.pitch, b.pitch), vFov, overlap).reversedArray(),
        )

        /** Evenly spaced tile centres whose tiles cover [lo, hi] with at least [overlap] between neighbours. */
        private fun centres(lo: Double, hi: Double, tile: Double, overlap: Double): DoubleArray {
            val span = hi - lo
            if (span <= tile) return doubleArrayOf((lo + hi) / 2)
            val n = ceil((span - tile) / (tile * (1 - overlap))).toInt() + 1
            val first = lo + tile / 2
            val last = hi - tile / 2
            return DoubleArray(n) { first + (last - first) * it / (n - 1) }
        }
    }
}
