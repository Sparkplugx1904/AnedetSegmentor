package com.conjunctiva.segmentation

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Custom View untuk menggambar overlay masking di atas kamera
 * Menggunakan transparent overlay agar performa tetap smooth
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results: List<SegmentationResult> = emptyList()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    
    // Paint untuk menggambar polygon
    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(100, 255, 0, 0) // Merah semi-transparan
        isAntiAlias = true
    }
    
    // Paint untuk border polygon
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    // Paint untuk bounding box
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.GREEN
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    // Paint untuk text
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    
    private val textBackgroundPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /**
     * Update hasil segmentasi dan trigger redraw
     */
    fun setResults(results: List<SegmentationResult>, imageWidth: Int, imageHeight: Int) {
        this.results = results
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate() // Trigger onDraw()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (results.isEmpty() || imageWidth == 0 || imageHeight == 0) {
            return
        }
        
        // Hitung scale factor untuk mapping dari koordinat gambar ke koordinat view
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight
        
        // Gambar setiap hasil deteksi
        for (result in results) {
            // 1. Gambar polygon masking
            drawPolygon(canvas, result.polygon, scaleX, scaleY)
            
            // 2. Gambar bounding box
            drawBoundingBox(canvas, result.boundingBox, scaleX, scaleY)
            
            // 3. Gambar label confidence
            drawLabel(canvas, result, scaleX, scaleY)
        }
    }

    private fun drawPolygon(
        canvas: Canvas,
        polygon: List<Pair<Float, Float>>,
        scaleX: Float,
        scaleY: Float
    ) {
        if (polygon.size < 3) return
        
        val path = Path()
        
        // Mulai dari titik pertama
        val firstPoint = polygon[0]
        path.moveTo(firstPoint.first * scaleX, firstPoint.second * scaleY)
        
        // Gambar garis ke titik-titik berikutnya
        for (i in 1 until polygon.size) {
            val point = polygon[i]
            path.lineTo(point.first * scaleX, point.second * scaleY)
        }
        
        // Tutup path
        path.close()
        
        // Gambar fill
        canvas.drawPath(path, maskPaint)
        
        // Gambar border
        canvas.drawPath(path, borderPaint)
    }

    private fun drawBoundingBox(
        canvas: Canvas,
        box: BoundingBox,
        scaleX: Float,
        scaleY: Float
    ) {
        val left = box.left * scaleX
        val top = box.top * scaleY
        val right = box.right * scaleX
        val bottom = box.bottom * scaleY
        
        canvas.drawRect(left, top, right, bottom, boxPaint)
    }

    private fun drawLabel(
        canvas: Canvas,
        result: SegmentationResult,
        scaleX: Float,
        scaleY: Float
    ) {
        val confidence = (result.confidence * 100).toInt()
        val label = "Conjunctiva ${confidence}%"
        
        // Posisi label di atas bounding box
        val x = result.boundingBox.left * scaleX
        val y = result.boundingBox.top * scaleY - 10
        
        // Ukur text
        val textBounds = Rect()
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        
        // Gambar background
        val padding = 10f
        canvas.drawRect(
            x - padding,
            y - textBounds.height() - padding,
            x + textBounds.width() + padding,
            y + padding,
            textBackgroundPaint
        )
        
        // Gambar text
        canvas.drawText(label, x, y, textPaint)
    }

    /**
     * Ubah warna masking
     */
    fun setMaskColor(color: Int, alpha: Int = 100) {
        maskPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }

    /**
     * Ubah ketebalan border
     */
    fun setBorderWidth(width: Float) {
        borderPaint.strokeWidth = width
        boxPaint.strokeWidth = width + 1
        invalidate()
    }
}
