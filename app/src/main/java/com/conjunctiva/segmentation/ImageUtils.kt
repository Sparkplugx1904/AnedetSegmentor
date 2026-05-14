package com.conjunctiva.segmentation

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * Optimized ImageUtils for high-performance frame conversion.
 */
object ImageUtils {
    private const val TAG = "ImageUtils"

    fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }
}
