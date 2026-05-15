package com.conjunctiva.segmentation

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

object ImageUtils {

    /**
     * Konversi ImageProxy (RGBA_8888) ke Bitmap dengan rotasi yang benar.
     *
     * KRITIS: Handle rowStride — di device nyata, rowStride > width * pixelStride
     * karena ada padding bytes di ujung setiap row. Tanpa ini gambar miring/korup.
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val width = imageProxy.width
        val height = imageProxy.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        if (rowStride == width * pixelStride) {
            // Fast path: tidak ada row padding
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            // Slow path: strip padding bytes di ujung setiap row
            val cleanBuffer = ByteBuffer.allocateDirect(width * height * pixelStride)
            buffer.rewind()
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                val rowBytes = ByteArray(width * pixelStride)
                buffer.get(rowBytes)
                cleanBuffer.put(rowBytes)
            }
            cleanBuffer.rewind()
            bitmap.copyPixelsFromBuffer(cleanBuffer)
        }

        // Rotasi agar bitmap orientasinya portrait benar
        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) rotateBitmap(bitmap, rotation) else bitmap
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated != src) {
            src.recycle()
        }
        return rotated
    }
}
