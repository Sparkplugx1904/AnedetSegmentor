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
 * Optimized OverlayView for real-time segmentation masking.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results: List<SegmentationResult> = emptyList()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    // Pre-allocated objects to avoid GC pressure during animation
    private val path = Path()
    private val textBounds = Rect()

    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 0, 255, 0) // Semi-transparent green for conjunctiva
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.GREEN
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private val textBgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    fun setResults(results: List<SegmentationResult>, imageWidth: Int, imageHeight: Int) {
        this.results = results
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (results.isEmpty() || imageWidth == 0 || imageHeight == 0) return

        val scale = minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f

        for (result in results) {
            drawMask(canvas, result.polygon, scale, offsetX, offsetY)
            drawLabel(canvas, result, scale, offsetX, offsetY)
        }
    }

    private fun drawMask(
        canvas: Canvas,
        polygon: List<Pair<Float, Float>>,
        scale: Float, offsetX: Float, offsetY: Float
    ) {
        if (polygon.size < 3) return

        path.reset()
        val first = polygon[0]
        path.moveTo(first.first * scale + offsetX, first.second * scale + offsetY)

        for (i in 1 until polygon.size) {
            val pt = polygon[i]
            path.lineTo(pt.first * scale + offsetX, pt.second * scale + offsetY)
        }
        path.close()

        canvas.drawPath(path, maskPaint)
        canvas.drawPath(path, borderPaint)
    }

    private fun drawLabel(
        canvas: Canvas,
        result: SegmentationResult,
        scale: Float, offsetX: Float, offsetY: Float
    ) {
        val label = "Conjunctiva ${(result.confidence * 100).toInt()}%"
        val x = result.boundingBox.left * scale + offsetX
        val y = (result.boundingBox.top * scale + offsetY).coerceAtLeast(40f)

        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val padding = 8f

        canvas.drawRect(
            x,
            y - textBounds.height() - padding * 2,
            x + textBounds.width() + padding * 2,
            y,
            textBgPaint
        )
        canvas.drawText(label, x + padding, y - padding, textPaint)
    }
}
