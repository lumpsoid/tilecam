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
import dev.tilecam.capture.GridPlan
import dev.tilecam.capture.ShootSession
import dev.tilecam.capture.TileState
import dev.tilecam.capture.TileTarget
import dev.tilecam.sensors.Aim
import dev.tilecam.sensors.Pose
import dev.tilecam.sensors.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Draws the shooting guidance on top of the preview:
 * captured tiles where they sit in the scene (so the overlap is visible), the next target,
 * a level line, an auto-capture hold ring and a minimap of the grid.
 * Everything is projected through the same pinhole model as the camera, so frames line up with the preview.
 */
class GuidanceOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var pose: Pose? = null
    var hFov = 40.0
    var vFov = 27.0
    /** First corner while the user is selecting the scene. */
    var cornerA: Pose? = null
    /** The grid the selection would give, while the user aims at the opposite corner. */
    var selection: GridPlan? = null
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
        val view = Aim.of(p)
        val s = session
        selection?.let { drawSelection(canvas, view, it) }
        if (s != null) drawShots(canvas, view, s)
        target?.let { drawTarget(canvas, view, it) }
        drawLevel(canvas, p)
        drawCrosshair(canvas)
        if (s != null) drawMinimap(canvas, p, s)
    }

    /** Screen position of the world ray [d] seen from [view], or false when it is behind the phone. */
    private fun project(view: Aim, d: Vec3, out: PointF): Boolean {
        val (x, y) = view.toImage(d) ?: return false
        out.set((width / 2 * (1 + x / tan(Math.toRadians(hFov / 2)))).toFloat(),
            (height / 2 * (1 - y / tan(Math.toRadians(vFov / 2)))).toFloat())
        return true
    }

    /**
     * Outline through world rays [corners] into [path]; false if any is behind the phone.
     * Straight lines stay straight under a pinhole projection, so the corners are enough.
     */
    private fun outline(view: Aim, corners: List<Vec3>): Boolean {
        path.reset()
        corners.forEachIndexed { i, d ->
            if (!project(view, d, pt)) return false
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        path.close()
        return true
    }

    /** The frame a camera at [yaw], [pitch], [roll] sees. */
    private fun frame(view: Aim, yaw: Double, pitch: Double, roll: Double): Boolean {
        val tile = Aim.of(yaw, pitch, roll)
        val x = tan(Math.toRadians(hFov / 2))
        val y = tan(Math.toRadians(vFov / 2))
        return outline(view, listOf(tile.ray(-x, y), tile.ray(x, y), tile.ray(x, -y), tile.ray(-x, -y)))
    }

    private fun drawSelection(c: Canvas, view: Aim, plan: GridPlan) {
        if (outline(view, plan.outline)) {
            fill.color = Color.argb(40, 255, 255, 255)
            c.drawPath(path, fill)
            c.drawPath(path, dashed)
        }
        val a = cornerA ?: return
        if (!project(view, Aim.of(a).forward, pt)) return
        fill.color = Color.YELLOW
        c.drawCircle(pt.x, pt.y, 8 * dp, fill)
    }

    private fun drawShots(c: Canvas, view: Aim, s: ShootSession) {
        for (shot in s.captured()) {
            if (!frame(view, shot.yaw, shot.pitch, shot.roll)) continue
            val color = stateColor(s.states[shot.index])
            fill.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
            stroke.color = color
            c.drawPath(path, fill)
            c.drawPath(path, stroke)
        }
    }

    private fun drawTarget(c: Canvas, view: Aim, t: TileTarget) {
        if (frame(view, t.yaw, t.pitch, 0.0)) c.drawPath(path, dashed)
        val d = Aim.of(t.yaw, t.pitch).forward
        val cx = width / 2f
        val cy = height / 2f
        val margin = 24 * dp
        val inFront = project(view, d, pt)
        val onScreen = inFront && pt.x in margin..(width - margin) && pt.y in margin..(height - margin)
        fill.color = Color.WHITE
        if (onScreen) {
            c.drawCircle(pt.x, pt.y, 10 * dp, fill)
            return
        }
        // Off screen: an arrow at the edge pointing towards the target (also when it is behind the phone).
        if (!inFront) pt.set(cx + (d dot view.right).toFloat(), cy - (d dot view.up).toFloat())
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
        stroke.color = if (abs(p.roll) < GridPlan.LEVEL_TOLERANCE) Color.GREEN else Color.rgb(255, 140, 0)
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
        val (gridCol, gridRow) = plan.gridPosition(p) ?: return
        val col = gridCol.coerceIn(-0.5, plan.cols - 0.5)
        val row = gridRow.coerceIn(-0.5, plan.rows - 0.5)
        stroke.color = Color.CYAN
        c.drawCircle((x0 + col * (cell + gap) + cell / 2).toFloat(), (y0 + row * (cell + gap) + cell / 2).toFloat(),
            cell / 3, stroke)
    }

    private fun stateColor(state: TileState) = when (state) {
        TileState.PENDING -> Color.argb(160, 120, 120, 120)
        TileState.CHECKING -> Color.YELLOW
        TileState.OK -> Color.GREEN
        TileState.WEAK -> Color.RED
    }
}
