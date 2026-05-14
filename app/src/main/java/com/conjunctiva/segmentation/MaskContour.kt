package com.conjunctiva.segmentation

import kotlin.math.hypot

/**
 * Kontur mask biner: komponen terbesar (filter noise), Moore-neighbor boundary tracing,
 * Douglas–Peucker simplification. Tanpa dependensi native.
 */
internal object MaskContour {

    /** 8-neighbor, clockwise from North (dy, dx) */
    private val mooreDy = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
    private val mooreDx = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    /**
     * @param roiBinary mask ROI [rh][rw], true = foreground
     * @param minArea minimal pixel count; komponen lebih kecil diabaikan
     */
    fun contourFromBinaryRoi(roiBinary: Array<BooleanArray>, minArea: Int): List<Pair<Float, Float>> {
        val filtered = keepLargestComponent(roiBinary, minArea) ?: return emptyList()
        val padded = padWithZeros(filtered, 1)
        val chain = mooreTraceOuter(padded) ?: return emptyList()
        // koordinat kembali ke ROI tanpa padding
        val shifted = chain.map { (x, y) -> Pair(x - 1f, y - 1f) }
        val sparse = subsample(shifted, minSpacing = 0.75f)
        return douglasPeucker(sparse, epsilon = 1.25f)
    }

    private fun padWithZeros(src: Array<BooleanArray>, pad: Int): Array<BooleanArray> {
        val h = src.size
        val w = src[0].size
        val out = Array(h + 2 * pad) { BooleanArray(w + 2 * pad) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[y + pad][x + pad] = src[y][x]
            }
        }
        return out
    }

    private fun keepLargestComponent(
        src: Array<BooleanArray>,
        minArea: Int
    ): Array<BooleanArray>? {
        val h = src.size
        val w = src[0].size
        val visited = Array(h) { BooleanArray(w) }
        var bestArea = 0
        val bestMask = Array(h) { BooleanArray(w) }

        val stack = IntArray(h * w * 2)
        for (sy in 0 until h) {
            for (sx in 0 until w) {
                if (!src[sy][sx] || visited[sy][sx]) continue
                var sp = 0
                stack[sp++] = sy
                stack[sp++] = sx
                visited[sy][sx] = true
                val comp = ArrayList<Pair<Int, Int>>(64)
                while (sp > 0) {
                    val x = stack[--sp]
                    val y = stack[--sp]
                    comp.add(Pair(y, x))
                    for (k in 0 until 8) {
                        val ny = y + mooreDy[k]
                        val nx = x + mooreDx[k]
                        if (ny in 0 until h && nx in 0 until w && src[ny][nx] && !visited[ny][nx]) {
                            visited[ny][nx] = true
                            stack[sp++] = ny
                            stack[sp++] = nx
                        }
                    }
                }
                if (comp.size > bestArea && comp.size >= minArea) {
                    bestArea = comp.size
                    for (yy in 0 until h) bestMask[yy].fill(false)
                    for ((yy, xx) in comp) bestMask[yy][xx] = true
                }
            }
        }
        return if (bestArea >= minArea) bestMask else null
    }

    /**
     * Moore-neighbor tracing pada gambar biner ter-padding (0 di border).
     */
    private fun mooreTraceOuter(padded: Array<BooleanArray>): List<Pair<Float, Float>>? {
        val h = padded.size
        val w = padded[0].size
        var sx = -1
        var sy = -1
        outer@ for (y in 0 until h) {
            for (x in 0 until w) {
                if (!padded[y][x]) continue
                if (!padded[y - 1][x]) {
                    sx = x
                    sy = y
                    break@outer
                }
            }
        }
        if (sx < 0) return null

        val contour = ArrayList<Pair<Float, Float>>(256)
        var cx = sx
        var cy = sy
        // datang dari barat relatif ke piksel awal (sisi kiri terbuka ke background)
        var backDir = 6
        val maxIter = h * w * 16 + 10
        var iter = 0
        while (iter++ < maxIter) {
            contour.add(Pair(cx.toFloat(), cy.toFloat()))
            var moved = false
            for (step in 0 until 8) {
                val dir = (backDir + 1 + step) % 8
                val nx = cx + mooreDx[dir]
                val ny = cy + mooreDy[dir]
                if (ny in 0 until h && nx in 0 until w && padded[ny][nx]) {
                    cx = nx
                    cy = ny
                    backDir = (dir + 4) % 8
                    moved = true
                    break
                }
            }
            if (!moved) break
            if (cx == sx && cy == sy && contour.size > 2) break
        }
        return if (contour.size >= 3) contour else null
    }

    private fun douglasPeucker(points: List<Pair<Float, Float>>, epsilon: Float): List<Pair<Float, Float>> {
        if (points.size < 3) return points
        fun perpDist(p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
            val dx = b.first - a.first
            val dy = b.second - a.second
            if (dx == 0f && dy == 0f) return hypot(p.first - a.first, p.second - a.second)
            val t = ((p.first - a.first) * dx + (p.second - a.second) * dy) / (dx * dx + dy * dy)
            val tClamped = t.coerceIn(0f, 1f)
            val projX = a.first + tClamped * dx
            val projY = a.second + tClamped * dy
            return hypot(p.first - projX, p.second - projY)
        }

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true

        fun simplify(i0: Int, i1: Int) {
            if (i1 <= i0 + 1) return
            var maxD = 0f
            var idx = i0
            val a = points[i0]
            val b = points[i1]
            for (i in i0 + 1 until i1) {
                val d = perpDist(points[i], a, b)
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (maxD > epsilon) {
                keep[idx] = true
                simplify(i0, idx)
                simplify(idx, i1)
            }
        }
        simplify(0, points.lastIndex)
        val out = ArrayList<Pair<Float, Float>>()
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return if (out.size >= 3) out else points
    }

    /** jarak rata-rata antar titik kontur untuk subsample sebelum RDP */
    fun subsample(points: List<Pair<Float, Float>>, minSpacing: Float): List<Pair<Float, Float>> {
        if (points.size < 4) return points
        val out = ArrayList<Pair<Float, Float>>(points.size / 2)
        out.add(points[0])
        var lx = points[0].first
        var ly = points[0].second
        val minSq = minSpacing * minSpacing
        for (i in 1 until points.lastIndex) {
            val p = points[i]
            val d = (p.first - lx) * (p.first - lx) + (p.second - ly) * (p.second - ly)
            if (d >= minSq) {
                out.add(p)
                lx = p.first
                ly = p.second
            }
        }
        out.add(points.last())
        return out
    }

    fun protoToImage(
        roiX: Int,
        roiY: Int,
        protoW: Int,
        protoH: Int,
        origW: Int,
        origH: Int,
        contour: List<Pair<Float, Float>>
    ): List<Pair<Float, Float>> {
        val sx = origW.toFloat() / protoW
        val sy = origH.toFloat() / protoH
        return contour.map { (fx, fy) ->
            Pair((roiX + fx) * sx, (roiY + fy) * sy)
        }
    }
}
