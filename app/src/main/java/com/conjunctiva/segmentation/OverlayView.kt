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

    private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
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

            // 4. Draw Crop Preview
            result.cropBitmap?.let { crop ->
                drawCropPreview(canvas, crop)
            }
        }
    }

    private fun drawMask(canvas: Canvas, maskBitmap: Bitmap, sx: Float, sy: Float) {
        val matrix = Matrix()

        // 1. Scale mask (160x160) to fit model input size (640x640)
        val maskToInputScale = 640f / maskBitmap.width
        matrix.postScale(maskToInputScale, maskToInputScale)

        // 2. Inverse Letterbox: Map from 640x640 back to original frame coordinates
        val scale = minOf(640f / imageWidth, 640f / imageHeight)
        val offsetX = (640f - imageWidth * scale) / 2f
        val offsetY = (640f - imageHeight * scale) / 2f

        // Corrected order for inverse mapping:
        // 1. Scale 640-space back to original frame space
        matrix.postScale(1f / scale, 1f / scale)
        // 2. Remove padding (offset must be scaled to original frame space)
        matrix.postTranslate(-offsetX / scale, -offsetY / scale)
        // 3. Scale original frame to View coordinates
        matrix.postScale(sx, sy)

        canvas.drawBitmap(maskBitmap, matrix, null)
    }

    private fun drawCropPreview(canvas: Canvas, cropBitmap: Bitmap) {
        val previewW = (width * 0.3f).toInt().coerceAtLeast(200)
        val previewH = (previewW * (cropBitmap.height.toFloat() / cropBitmap.width)).toInt()
        val margin = 20f

        val destRect = RectF(margin, margin, margin + previewW, margin + previewH)
        canvas.drawBitmap(cropBitmap, null, destRect, null)
        canvas.drawRect(destRect, cropBorderPaint)

        canvas.drawText("CROP", margin + 10f, destRect.bottom - 10f, Paint(textPaint).apply { textSize = 30f })
    }
}
