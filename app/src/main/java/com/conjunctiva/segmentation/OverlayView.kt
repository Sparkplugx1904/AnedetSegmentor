package com.conjunctiva.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Optimized OverlayView for real-time segmentation masking using Bitmap overlay.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var result: ConjunctivaSegmentor.SegmentationResult? = null
    private var maskBitmap: Bitmap? = null
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    private val textBounds = Rect()
    private val destRect = Rect()

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

    fun setResults(result: ConjunctivaSegmentor.SegmentationResult?, imageWidth: Int, imageHeight: Int) {
        this.result = result
        this.maskBitmap = result?.mask
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentMask = maskBitmap
        if (currentMask == null || imageWidth == 0 || imageHeight == 0) return

        // Calculate scaling to fit view while maintaining aspect ratio
        val scale = minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val drawW = imageWidth * scale
        val drawH = imageHeight * scale
        val offsetX = (width - drawW) / 2f
        val offsetY = (height - drawH) / 2f

        // Draw Mask Bitmap
        destRect.set(
            offsetX.toInt(),
            offsetY.toInt(),
            (offsetX + drawW).toInt(),
            (offsetY + drawH).toInt()
        )
        canvas.drawBitmap(currentMask, null, destRect, null)

        // Draw label
        result?.let {
            val label = "Conjunctiva ${(it.confidence * 100).toInt()}%"
            val x = it.boundingBox.left * (drawW / imageWidth) + offsetX
            val y = (it.boundingBox.top * (drawH / imageHeight) + offsetY).coerceAtLeast(40f)

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
}
