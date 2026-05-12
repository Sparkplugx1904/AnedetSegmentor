package com.conjunctiva.segmentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Custom View untuk menggambar overlay segmentasi di atas PreviewView kamera.
 *
 * Bug #4 Fix — Coordinate Mapping:
 * PreviewView menggunakan scaleType="fillCenter" (fit-center / letterbox), bukan
 * stretch. OverlayView harus mengikuti logika yang sama: hitung scale tunggal
 * (min dari scaleX & scaleY) dan tambahkan offset padding di sisi yang kosong,
 * supaya bounding-box dan polygon tepat sejajar dengan gambar yang tampil.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results:     List<SegmentationResult> = emptyList()
    private var imageWidth:  Int = 0
    private var imageHeight: Int = 0

    // ── Paints ───────────────────────────────────────────────────────────────

    private val maskPaint = Paint().apply {
        style       = Paint.Style.FILL
        color       = Color.argb(110, 255, 50, 50)
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        style       = Paint.Style.STROKE
        color       = Color.rgb(255, 80, 80)
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val boxPaint = Paint().apply {
        style       = Paint.Style.STROKE
        color       = Color.rgb(0, 230, 100)
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color       = Color.WHITE
        textSize    = 42f
        isAntiAlias = true
        typeface    = Typeface.DEFAULT_BOLD
    }

    private val textBgPaint = Paint().apply {
        color = Color.argb(190, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setResults(results: List<SegmentationResult>, imageWidth: Int, imageHeight: Int) {
        this.results     = results
        this.imageWidth  = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (results.isEmpty() || imageWidth == 0 || imageHeight == 0) return

        // Bug #4 Fix ─ Fit-center (letterbox) scaling, sama seperti PreviewView fillCenter
        // Hitung scale tunggal yang mempertahankan aspect ratio gambar
        val scale   = minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        // Offset padding di sisi yang tidak terisi (letter/pillarbox)
        val offsetX = (width  - imageWidth  * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f

        for (result in results) {
            drawPolygon(canvas, result.polygon,     scale, offsetX, offsetY)
            drawBoundingBox(canvas, result.boundingBox, scale, offsetX, offsetY)
            drawLabel(canvas, result,                   scale, offsetX, offsetY)
        }
    }

    /**
     * Konversi koordinat gambar ke koordinat view menggunakan fit-center transform.
     */
    private fun imgToViewX(x: Float, scale: Float, offsetX: Float) = x * scale + offsetX
    private fun imgToViewY(y: Float, scale: Float, offsetY: Float) = y * scale + offsetY

    private fun drawPolygon(
        canvas:  Canvas,
        polygon: List<Pair<Float, Float>>,
        scale:   Float, offsetX: Float, offsetY: Float
    ) {
        if (polygon.size < 3) return

        val path = Path()
        val first = polygon[0]
        path.moveTo(imgToViewX(first.first, scale, offsetX),
                    imgToViewY(first.second, scale, offsetY))

        for (i in 1 until polygon.size) {
            val pt = polygon[i]
            path.lineTo(imgToViewX(pt.first, scale, offsetX),
                        imgToViewY(pt.second, scale, offsetY))
        }
        path.close()

        canvas.drawPath(path, maskPaint)
        canvas.drawPath(path, borderPaint)
    }

    private fun drawBoundingBox(
        canvas: Canvas,
        box:    BoundingBox,
        scale:  Float, offsetX: Float, offsetY: Float
    ) {
        val l = imgToViewX(box.left,   scale, offsetX)
        val t = imgToViewY(box.top,    scale, offsetY)
        val r = imgToViewX(box.right,  scale, offsetX)
        val b = imgToViewY(box.bottom, scale, offsetY)
        canvas.drawRect(l, t, r, b, boxPaint)
    }

    private fun drawLabel(
        canvas:  Canvas,
        result:  SegmentationResult,
        scale:   Float, offsetX: Float, offsetY: Float
    ) {
        val pct   = (result.confidence * 100).toInt()
        val label = "Conjunctiva $pct%"

        val x = imgToViewX(result.boundingBox.left, scale, offsetX)
        val y = imgToViewY(result.boundingBox.top,  scale, offsetY) - 12f

        val bounds = Rect()
        textPaint.getTextBounds(label, 0, label.length, bounds)
        val pad = 10f

        canvas.drawRect(
            x - pad,
            y - bounds.height() - pad,
            x + bounds.width() + pad,
            y + pad,
            textBgPaint
        )
        canvas.drawText(label, x, y, textPaint)
    }

    // ── Utility setters ───────────────────────────────────────────────────────

    fun setMaskColor(color: Int, alpha: Int = 110) {
        maskPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }

    fun setBorderWidth(strokeWidth: Float) {
        borderPaint.strokeWidth = strokeWidth
        boxPaint.strokeWidth    = strokeWidth + 1f
        invalidate()
    }
}
