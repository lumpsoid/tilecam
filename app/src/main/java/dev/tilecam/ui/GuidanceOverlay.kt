package dev.tilecam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.tilecam.capture.ShootSession
import dev.tilecam.capture.TileState
import dev.tilecam.capture.TileTarget
import dev.tilecam.sensors.Pose
import dev.tilecam.sensors.wrapDegrees
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the shooting guidance on top of the preview:
 * captured tiles where they sit in the scene (so the overlap is visible), the next target,
 * a level line, an auto-capture hold ring and a minimap of the grid.
 */
class GuidanceOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var pose: Pose? = null
    var hFov = 40.0
    var vFov = 27.0
    /** First corner while the user is selecting the scene. */
    var cornerA: Pose? = null
    var session: ShootSession? = null
    var target: TileTarget? = null
    /** Auto-capture progress, 0..1. */
    var hold = 0f

    private val dp = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dashed = Paint(stroke).apply {
        color = Color.WHITE
        strokeWidth = 3 * dp
        pathEffect = DashPathEffect(floatArrayOf(12 * dp, 8 * dp), 0f)
    }
    private val path = Path()
    private val pt = PointF()

    override fun onDraw(canvas: Canvas) {
        val p = pose ?: return
        val ppd = width / hFov
        val s = session
        if (s == null) cornerA?.let { drawSelection(canvas, p, it, ppd) }
        if (s != null) drawShots(canvas, p, s, ppd)
        target?.let { drawTarget(canvas, p, it, ppd) }
        drawLevel(canvas, p)
        drawCrosshair(canvas)
        if (s != null) drawMinimap(canvas, p, s)
    }

    /** Scene angles to screen pixels, accounting for the phone's roll. */
    private fun project(p: Pose, yaw: Double, pitch: Double, ppd: Double, out: PointF) {
        val dx = wrapDegrees(yaw - p.yaw) * cos(Math.toRadians((pitch + p.pitch) / 2))
        val dy = -(pitch - p.pitch)
        val r = Math.toRadians(p.roll)
        val x = dx * cos(r) - dy * sin(r)
        val y = dx * sin(r) + dy * cos(r)
        out.set((width / 2 + x * ppd).toFloat(), (height / 2 + y * ppd).toFloat())
    }

    private fun quad(p: Pose, yaw: Double, pitch: Double, halfYaw: Double, halfPitch: Double, ppd: Double): Path {
        path.reset()
        val corners = arrayOf(-1.0 to 1.0, 1.0 to 1.0, 1.0 to -1.0, -1.0 to -1.0)
        corners.forEachIndexed { i, (sx, sy) ->
            project(p, yaw + sx * halfYaw, pitch + sy * halfPitch, ppd, pt)
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        path.close()
        return path
    }

    private fun tileQuad(p: Pose, yaw: Double, pitch: Double, ppd: Double) =
        quad(p, yaw, pitch, hFov / 2 / cos(Math.toRadians(pitch)), vFov / 2, ppd)

    private fun drawSelection(c: Canvas, p: Pose, a: Pose, ppd: Double) {
        val halfYaw = wrapDegrees(p.yaw - a.yaw) / 2
        val halfPitch = (p.pitch - a.pitch) / 2
        quad(p, a.yaw + halfYaw, a.pitch + halfPitch, abs(halfYaw), abs(halfPitch), ppd)
        fill.color = Color.argb(40, 255, 255, 255)
        c.drawPath(path, fill)
        c.drawPath(path, dashed)
        project(p, a.yaw, a.pitch, ppd, pt)
        fill.color = Color.YELLOW
        c.drawCircle(pt.x, pt.y, 8 * dp, fill)
    }

    private fun drawShots(c: Canvas, p: Pose, s: ShootSession, ppd: Double) {
        for (shot in s.captured()) {
            val color = stateColor(s.states[shot.index])
            tileQuad(p, shot.yaw, shot.pitch, ppd)
            fill.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
            stroke.color = color
            c.drawPath(path, fill)
            c.drawPath(path, stroke)
        }
    }

    private fun drawTarget(c: Canvas, p: Pose, t: TileTarget, ppd: Double) {
        c.drawPath(tileQuad(p, t.yaw, t.pitch, ppd), dashed)
        project(p, t.yaw, t.pitch, ppd, pt)
        val cx = width / 2f
        val cy = height / 2f
        val margin = 24 * dp
        val onScreen = pt.x in margin..(width - margin) && pt.y in margin..(height - margin)
        fill.color = Color.WHITE
        if (onScreen) {
            c.drawCircle(pt.x, pt.y, 10 * dp, fill)
            return
        }
        // Off screen: an arrow at the edge pointing towards the target.
        val dx = pt.x - cx
        val dy = pt.y - cy
        val len = hypot(dx, dy)
        val scale = min((cx - margin) / abs(dx).coerceAtLeast(1f), (cy - margin) / abs(dy).coerceAtLeast(1f))
        val ex = cx + dx * scale
        val ey = cy + dy * scale
        val ux = dx / len
        val uy = dy / len
        val size = 22 * dp
        path.reset()
        path.moveTo(ex, ey)
        path.lineTo(ex - ux * size - uy * size / 2, ey - uy * size + ux * size / 2)
        path.lineTo(ex - ux * size + uy * size / 2, ey - uy * size - ux * size / 2)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawLevel(c: Canvas, p: Pose) {
        val r = Math.toRadians(p.roll)
        val half = width * 0.15f
        val dx = (cos(r) * half).toFloat()
        val dy = (sin(r) * half).toFloat()
        stroke.color = if (abs(p.roll) < LEVEL_TOLERANCE) Color.GREEN else Color.rgb(255, 140, 0)
        c.drawLine(width / 2f - dx, height / 2f - dy, width / 2f + dx, height / 2f + dy, stroke)
    }

    private fun drawCrosshair(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 18 * dp
        stroke.color = Color.WHITE
        c.drawLine(cx - r, cy, cx + r, cy, stroke)
        c.drawLine(cx, cy - r, cx, cy + r, stroke)
        if (hold > 0f) {
            stroke.color = Color.GREEN
            stroke.strokeWidth = 4 * dp
            c.drawArc(RectF(cx - 2 * r, cy - 2 * r, cx + 2 * r, cy + 2 * r), -90f, 360f * hold, false, stroke)
            stroke.strokeWidth = 2 * dp
        }
    }

    private fun drawMinimap(c: Canvas, p: Pose, s: ShootSession) {
        val plan = s.plan
        val cell = 16 * dp
        val gap = 2 * dp
        val x0 = 16 * dp
        val y0 = 16 * dp
        fill.color = Color.argb(140, 0, 0, 0)
        c.drawRect(x0 - gap * 3, y0 - gap * 3, x0 + plan.cols * (cell + gap) + gap * 2,
            y0 + plan.rows * (cell + gap) + gap * 2, fill)
        for (t in plan.targets) {
            val left = x0 + t.col * (cell + gap)
            val top = y0 + t.row * (cell + gap)
            fill.color = if (t == target) Color.WHITE else stateColor(s.states[t.index])
            c.drawRect(left, top, left + cell, top + cell, fill)
        }
        // Where the camera points now, in grid units.
        val col = fraction(p.yaw, plan.colYaws).coerceIn(-0.5, plan.cols - 0.5)
        val row = fraction(p.pitch, plan.rowPitches).coerceIn(-0.5, plan.rows - 0.5)
        stroke.color = Color.CYAN
        c.drawCircle((x0 + col * (cell + gap) + cell / 2).toFloat(), (y0 + row * (cell + gap) + cell / 2).toFloat(),
            cell / 3, stroke)
    }

    private fun fraction(v: Double, centres: DoubleArray): Double =
        if (centres.size < 2) 0.0 else (v - centres[0]) / (centres[1] - centres[0])

    private fun stateColor(state: TileState) = when (state) {
        TileState.PENDING -> Color.argb(160, 120, 120, 120)
        TileState.CHECKING -> Color.YELLOW
        TileState.OK -> Color.GREEN
        TileState.WEAK -> Color.RED
    }

    companion object {
        const val LEVEL_TOLERANCE = 3.0
    }
}
