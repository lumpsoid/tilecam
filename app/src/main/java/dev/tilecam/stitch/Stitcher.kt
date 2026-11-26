package dev.tilecam.stitch

import dev.tilecam.capture.Shot
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Merges tiles into one image on a flat (rectilinear) projection centred on the middle tile.
 *
 * 1. SIFT features on quarter-resolution copies.
 * 2. A homography for every pair of neighbouring tiles, rejected if it disagrees with the gyro.
 * 3. Tiles are chained to the centre tile along the strongest matches (maximum spanning tree).
 *    Tiles without a usable match are placed from the gyro instead.
 * 4. Full-resolution compositing in horizontal strips with feathered blending, to bound memory.
 */
object Stitcher {

    const val MIN_INLIERS = 30
    private const val RATIO = 0.75f
    private const val STRIP_PIXELS = 4_000_000L
    private const val TILE_CACHE = 6

    class Features(val keypoints: MatOfKeyPoint, val descriptors: Mat, val width: Int) {
        fun release() {
            keypoints.release()
            descriptors.release()
        }
    }

    class Match(val homography: DoubleArray, val inliers: Int)

    class Result(val file: File, val width: Int, val height: Int, val gyroPlaced: Int)

    fun detect(file: File): Features {
        val gray = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_REDUCED_GRAYSCALE_4)
        require(!gray.empty()) { "Could not read ${file.name}" }
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        SIFT.create(2000).detectAndCompute(gray, Mat(), keypoints, descriptors)
        val width = gray.cols()
        gray.release()
        return Features(keypoints, descriptors, width)
    }

    /** Homography mapping [b]'s work-scale pixels onto [a]'s, or null if they don't overlap convincingly. */
    fun match(a: Features, b: Features): Match? {
        if (a.descriptors.rows() < 2 || b.descriptors.rows() < 2) return null
        val knn = ArrayList<MatOfDMatch>()
        BFMatcher.create(Core.NORM_L2, false).knnMatch(b.descriptors, a.descriptors, knn, 2)
        val kpA = a.keypoints.toArray()
        val kpB = b.keypoints.toArray()
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        for (m in knn) {
            val d = m.toArray()
            if (d.size == 2 && d[0].distance < RATIO * d[1].distance) {
                src += kpB[d[0].queryIdx].pt
                dst += kpA[d[0].trainIdx].pt
            }
            m.release()
        }
        if (src.size < MIN_INLIERS) return null
        val mask = Mat()
        val h = Calib3d.findHomography(
            MatOfPoint2f(*src.toTypedArray()), MatOfPoint2f(*dst.toTypedArray()), Calib3d.RANSAC, 3.0, mask)
        if (h.empty()) return null
        val inliers = Core.countNonZero(mask)
        mask.release()
        if (inliers < MIN_INLIERS) return null
        return Match(DoubleArray(9).also { h.get(0, 0, it) }, inliers)
    }

    fun stitch(
        shots: List<Shot>,
        hFovDeg: Double,
        out: File,
        maxPixels: Long = 50_000_000L,
        progress: (String) -> Unit,
    ): Result {
        require(shots.size >= 2) { "Need at least two tiles" }
        val n = shots.size
        val w = shots[0].width
        val h = shots[0].height

        val features = shots.mapIndexed { i, s ->
            progress("Finding features ${i + 1}/$n")
            detect(s.file)
        }
        try {
            val k = intrinsics(w, h, hFovDeg)
            val kInv = H3.inv(k)
            val rotations = shots.map { rotation(it.yaw, it.pitch, it.roll) }
            /** Gyro-only homography from tile [src]'s pixels to tile [dst]'s. */
            fun gyro(dst: Int, src: Int) =
                H3.mul(k, H3.mul(H3.transpose(rotations[dst]), H3.mul(rotations[src], kInv)))

            // Pairwise matches between grid neighbours.
            class Edge(val a: Int, val b: Int, val bToA: DoubleArray, val inliers: Int)
            val edges = ArrayList<Edge>()
            for (i in 0 until n) for (j in i + 1 until n) {
                if (abs(shots[i].row - shots[j].row) > 1 || abs(shots[i].col - shots[j].col) > 1) continue
                progress("Matching tiles ${i + 1} and ${j + 1}")
                val m = match(features[i], features[j]) ?: continue
                val s = features[i].width.toDouble() / w
                val full = H3.mul(H3.scale(1 / s, 1 / s), H3.mul(m.homography, H3.scale(s, s)))
                if (agrees(full, gyro(i, j), w, h)) edges += Edge(i, j, full, m.inliers)
            }

            // Chain every tile to the centre one along the strongest matches.
            val midRow = shots.map { it.row }.average()
            val midCol = shots.map { it.col }.average()
            val ref = shots.indices.minBy { hypot(shots[it].row - midRow, shots[it].col - midCol) }
            val toRef = arrayOfNulls<DoubleArray>(n)
            toRef[ref] = H3.identity()
            while (true) {
                val e = edges.filter { (toRef[it.a] != null) != (toRef[it.b] != null) }.maxByOrNull { it.inliers } ?: break
                if (toRef[e.a] != null) toRef[e.b] = H3.mul(toRef[e.a]!!, e.bToA)
                else toRef[e.a] = H3.mul(toRef[e.b]!!, H3.inv(e.bToA))
            }
            var gyroPlaced = 0
            while (toRef.any { it == null }) {
                val (p, u) = shots.indices.filter { toRef[it] != null }
                    .flatMap { p -> shots.indices.filter { toRef[it] == null }.map { p to it } }
                    .minBy { (p, u) -> hypot((shots[p].row - shots[u].row).toDouble(), (shots[p].col - shots[u].col).toDouble()) }
                toRef[u] = H3.mul(toRef[p]!!, gyro(p, u))
                gyroPlaced++
            }

            // Canvas bounds.
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (g in toRef) for ((x, y) in corners(w, h)) {
                val q = H3.apply(g!!, x, y)
                    ?: error("The scene is too wide for a flat projection. Shoot a smaller area.")
                minX = min(minX, q[0]); maxX = max(maxX, q[0])
                minY = min(minY, q[1]); maxY = max(maxY, q[1])
            }
            val area = (maxX - minX) * (maxY - minY)
            check(area < 4.0 * n * w * h) { "Alignment failed: the tiles don't fit together." }
            val scale = min(1.0, sqrt(maxPixels / area))
            val canvasW = ceil((maxX - minX) * scale).toInt()
            val canvasH = ceil((maxY - minY) * scale).toInt()
            val toCanvas = H3.mul(H3.scale(scale, scale), H3.translate(-minX, -minY))
            val transforms = toRef.map { H3.mul(toCanvas, it!!) }

            compose(shots, transforms, canvasW, canvasH, out, progress)
            return Result(out, canvasW, canvasH, gyroPlaced)
        } finally {
            features.forEach { it.release() }
        }
    }

    private fun compose(
        shots: List<Shot>, transforms: List<DoubleArray>, canvasW: Int, canvasH: Int, out: File,
        progress: (String) -> Unit,
    ) {
        val w = shots[0].width
        val h = shots[0].height
        // Feather weights at 1/8 resolution: 1 in the middle of a tile, falling to 0 at its edges.
        val ws = w / 8
        val hs = h / 8
        val weight = Mat(hs, ws, CvType.CV_32F).apply {
            val data = FloatArray(ws * hs)
            for (y in 0 until hs) for (x in 0 until ws) {
                data[y * ws + x] = (min(min(x + 1, ws - x), min(y + 1, hs - y)).toFloat() / (min(ws, hs) / 2f)).coerceAtMost(1f)
            }
            put(0, 0, data)
        }
        val up = H3.scale(w.toDouble() / ws, h.toDouble() / hs)

        val boxes = transforms.map { t ->
            val pts = corners(w, h).map { (x, y) -> H3.apply(t, x, y)!! }
            Rect(
                floor(pts.minOf { it[0] }).toInt().coerceIn(0, canvasW),
                floor(pts.minOf { it[1] }).toInt().coerceIn(0, canvasH),
                0, 0,
            ).also {
                it.width = ceil(pts.maxOf { p -> p[0] }).toInt().coerceIn(0, canvasW) - it.x
                it.height = ceil(pts.maxOf { p -> p[1] }).toInt().coerceIn(0, canvasH) - it.y
            }
        }

        val cache = object : LinkedHashMap<Int, Mat>(TILE_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Mat>): Boolean =
                (size > TILE_CACHE).also { if (it) eldest.value.release() }
        }
        fun image(i: Int) = cache.getOrPut(i) { Imgcodecs.imread(shots[i].file.path, Imgcodecs.IMREAD_COLOR) }

        val canvas = Mat(canvasH, canvasW, CvType.CV_8UC3, Scalar.all(0.0))
        val stripRows = max(64, (STRIP_PIXELS / canvasW).toInt())
        val warped = Mat()
        val warpedF = Mat()
        val warpedW = Mat()
        val w3 = Mat()
        var y0 = 0
        while (y0 < canvasH) {
            progress("Blending ${100 * y0 / canvasH}%")
            val sh = min(stripRows, canvasH - y0)
            val accum = Mat.zeros(sh, canvasW, CvType.CV_32FC3)
            val wsum = Mat.zeros(sh, canvasW, CvType.CV_32FC1)
            for (i in shots.indices) {
                val b = boxes[i]
                val top = max(b.y, y0)
                val bottom = min(b.y + b.height, y0 + sh)
                if (top >= bottom || b.width <= 0) continue
                val roi = Rect(b.x, top - y0, b.width, bottom - top)
                val toRoi = H3.mul(H3.translate(-b.x.toDouble(), -top.toDouble()), transforms[i])
                val size = Size(roi.width.toDouble(), roi.height.toDouble())
                Imgproc.warpPerspective(image(i), warped, H3.toMat(toRoi), size,
                    Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(0.0))
                Imgproc.warpPerspective(weight, warpedW, H3.toMat(H3.mul(toRoi, up)), size,
                    Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(0.0))
                warped.convertTo(warpedF, CvType.CV_32FC3)
                Core.merge(listOf(warpedW, warpedW, warpedW), w3)
                Core.multiply(warpedF, w3, warpedF)
                val a = accum.submat(roi)
                Core.add(a, warpedF, a)
                val s = wsum.submat(roi)
                Core.add(s, warpedW, s)
            }
            Core.max(wsum, Scalar(1e-6), wsum)
            Core.merge(listOf(wsum, wsum, wsum), w3)
            Core.divide(accum, w3, accum)
            accum.convertTo(canvas.submat(Rect(0, y0, canvasW, sh)), CvType.CV_8UC3)
            accum.release()
            wsum.release()
            y0 += sh
        }
        progress("Saving")
        Imgcodecs.imwrite(out.path, canvas, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92))
        listOf(canvas, warped, warpedF, warpedW, w3, weight).forEach { it.release() }
        cache.values.forEach { it.release() }
    }

    private fun corners(w: Int, h: Int) = listOf(0.0 to 0.0, w.toDouble() to 0.0, w.toDouble() to h.toDouble(), 0.0 to h.toDouble())

    /** A feature match must land within a quarter frame of where the gyro says it should. */
    private fun agrees(matched: DoubleArray, gyro: DoubleArray, w: Int, h: Int): Boolean {
        val a = H3.apply(matched, w / 2.0, h / 2.0) ?: return false
        val b = H3.apply(gyro, w / 2.0, h / 2.0) ?: return false
        return hypot(a[0] - b[0], a[1] - b[1]) < 0.25 * w
    }

    private fun intrinsics(w: Int, h: Int, hFovDeg: Double): DoubleArray {
        val f = (w / 2.0) / tan(Math.toRadians(hFovDeg) / 2)
        return doubleArrayOf(f, 0.0, w / 2.0, 0.0, f, h / 2.0, 0.0, 0.0, 1.0)
    }

    /**
     * Camera-to-world rotation in camera axes (x right, y down, z forward):
     * yaw turns right about y, pitch tilts up about x, roll raises the right side about z.
     */
    private fun rotation(yawDeg: Double, pitchDeg: Double, rollDeg: Double): DoubleArray {
        val y = Math.toRadians(yawDeg)
        val p = Math.toRadians(pitchDeg)
        val r = Math.toRadians(rollDeg)
        val ry = doubleArrayOf(cos(y), 0.0, sin(y), 0.0, 1.0, 0.0, -sin(y), 0.0, cos(y))
        val rx = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(p), -sin(p), 0.0, sin(p), cos(p))
        val rz = doubleArrayOf(cos(r), sin(r), 0.0, -sin(r), cos(r), 0.0, 0.0, 0.0, 1.0)
        return H3.mul(ry, H3.mul(rx, rz))
    }
}

/** Row-major 3×3 matrix helpers. */
private object H3 {
    fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    fun scale(sx: Double, sy: Double) = doubleArrayOf(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0)
    fun translate(tx: Double, ty: Double) = doubleArrayOf(1.0, 0.0, tx, 0.0, 1.0, ty, 0.0, 0.0, 1.0)

    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun transpose(a: DoubleArray) = DoubleArray(9) { i -> a[(i % 3) * 3 + i / 3] }

    fun inv(m: DoubleArray): DoubleArray {
        val (a, b, c, d, e) = m
        val f = m[5]; val g = m[6]; val h = m[7]; val k = m[8]
        val det = a * (e * k - f * h) - b * (d * k - f * g) + c * (d * h - e * g)
        return doubleArrayOf(
            (e * k - f * h) / det, (c * h - b * k) / det, (b * f - c * e) / det,
            (f * g - d * k) / det, (a * k - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    /** Null when the point lands behind the projection plane. */
    fun apply(m: DoubleArray, x: Double, y: Double): DoubleArray? {
        val w = m[6] * x + m[7] * y + m[8]
        if (w <= 1e-9) return null
        return doubleArrayOf((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    fun toMat(m: DoubleArray) = Mat(3, 3, CvType.CV_64F).apply { put(0, 0, *m) }
}
