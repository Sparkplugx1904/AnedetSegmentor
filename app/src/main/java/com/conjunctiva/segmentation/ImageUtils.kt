package com.conjunctiva.segmentation

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Optimized ImageUtils for high-performance frame conversion.
 */
object ImageUtils {

    /**
     * Converts ImageProxy to Bitmap using the most direct path possible.
     * Currently optimized for RGBA_8888 as per MainActivity configuration.
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        if (imageProxy.format != ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) {
            // Fallback for YUV or other formats if needed, but RGBA is preferred for speed
            return fallbackToJpeg(imageProxy)
        }

        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width

        val bitmap = Bitmap.createBitmap(
            imageProxy.width + rowPadding / pixelStride,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // If rowPadding > 0, we need to crop the bitmap to the actual image size
        val finalBitmap = if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height)
        } else {
            bitmap
        }

        return rotateBitmap(finalBitmap, imageProxy.imageInfo.rotationDegrees)
    }

    private fun fallbackToJpeg(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
