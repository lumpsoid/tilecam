package dev.tilecam.capture

import dev.tilecam.sensors.Pose
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

enum class TileState { PENDING, CHECKING, OK, WEAK }

/** A captured tile and the pose it was taken at. */
data class Shot(
    val index: Int, val row: Int, val col: Int,
    val yaw: Double, val pitch: Double, val roll: Double,
    val file: File, val width: Int, val height: Int,
)

/**
 * Progress through one grid. Lives on the main thread; only [writeJpeg] may run elsewhere.
 * Everything is mirrored to session.json so a session can be re-stitched later (for example on a computer).
 */
class ShootSession(val dir: File, val plan: GridPlan, val hFov: Double, val vFov: Double) {

    val states = Array(plan.size) { TileState.PENDING }
    private val shots = arrayOfNulls<Shot>(plan.size)
    private val attempts = IntArray(plan.size)

    init {
        dir.mkdirs()
        save()
    }

    /** The next tile to shoot. A tile that failed its overlap check is offered again, up to [MAX_ATTEMPTS]. */
    fun next(): TileTarget? = plan.targets.firstOrNull {
        val s = states[it.index]
        s == TileState.PENDING || (s == TileState.WEAK && attempts[it.index] < MAX_ATTEMPTS)
    }

    fun fileFor(t: TileTarget) = File(dir, "tile_r${t.row}_c${t.col}_${attempts[t.index] + 1}.jpg")

    fun writeJpeg(file: File, jpeg: ByteArray) = file.writeBytes(jpeg)

    fun record(t: TileTarget, file: File, pose: Pose, width: Int, height: Int): Shot {
        shots[t.index]?.file?.takeIf { it != file }?.delete()
        attempts[t.index]++
        val shot = Shot(t.index, t.row, t.col, pose.yaw, pose.pitch, pose.roll, file, width, height)
        shots[t.index] = shot
        states[t.index] = TileState.CHECKING
        save()
        return shot
    }

    fun setState(index: Int, state: TileState) {
        states[index] = state
        save()
    }

    fun captured(): List<Shot> = shots.filterNotNull()

    fun capturedNeighbours(shot: Shot): List<Shot> = captured().filter {
        it.index != shot.index && abs(it.row - shot.row) <= 1 && abs(it.col - shot.col) <= 1
    }

    private fun save() {
        val tiles = JSONArray()
        for (s in captured()) {
            tiles.put(JSONObject()
                .put("index", s.index).put("row", s.row).put("col", s.col)
                .put("yaw", s.yaw).put("pitch", s.pitch).put("roll", s.roll)
                .put("file", s.file.name).put("width", s.width).put("height", s.height)
                .put("state", states[s.index].name))
        }
        val json = JSONObject()
            .put("hFov", hFov).put("vFov", vFov)
            .put("rows", plan.rows).put("cols", plan.cols)
            .put("tiles", tiles)
        File(dir, "session.json").writeText(json.toString(2))
    }

    companion object {
        const val MAX_ATTEMPTS = 2
    }
}
