package com.conjunctiva.segmentation

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var results: List<SegmentResult> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private val bboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setResults(results: List<SegmentResult>, width: Int, height: Int) {
        this.results = results
        this.imageWidth = width
        this.imageHeight = height
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        results.forEach { result ->
            // 1. Draw Mask
            result.maskBitmap?.let { mask ->
                drawMask(canvas, mask, scaleX, scaleY)
            }

            // 2. Draw Bounding Box
            val scaledRect = RectF(
                result.boundingBox.left * scaleX,
                result.boundingBox.top * scaleY,
                result.boundingBox.right * scaleX,
                result.boundingBox.bottom * scaleY
            )
            canvas.drawRect(scaledRect, bboxPaint)

            // 3. Draw Label
            val label = "Conjunctiva: ${(result.confidence * 100).toInt()}%"
            canvas.drawText(label, scaledRect.left, scaledRect.top - 10f, textPaint)
        }
    }

    private fun drawMask(canvas: Canvas, maskBitmap: Bitmap, sx: Float, sy: Float) {
        // Correcting Inverse Letterbox Mapping for Overlay
        // Mask 160x160 -> Model Input 640x640
        // Mapping mask pixels to original image pixels

        val matrix = Matrix()

        // 1. Scale mask (160x160) to fit model input size (640x640)
        val maskToInputScale = 640f / maskBitmap.width
        matrix.postScale(maskToInputScale, maskToInputScale)

        // 2. Map from 640x640 back to original frame coordinates
        val scale = minOf(640f / imageWidth, 640f / imageHeight)
        val offsetX = (640f - imageWidth * scale) / 2f
        val offsetY = (640f - imageHeight * scale) / 2f

        matrix.postTranslate(-offsetX, -offsetY)
        matrix.postScale(1f/scale, 1f/scale)

        // 3. Scale to View coordinates
        matrix.postScale(sx, sy)

        canvas.drawBitmap(maskBitmap, matrix, null)
    }
}
