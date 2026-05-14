package com.conjunctiva.segmentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Optimized ImageUtils for high-performance frame conversion.
 */
object ImageUtils {
    private const val TAG = "ImageUtils"

    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val format = imageProxy.format

        return when (format) {
            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888 -> {
                rgbaToBitmap(imageProxy)
            }
            ImageFormat.YUV_420_888 -> {
                yuvToBitmap(imageProxy)
            }
            else -> {
                Log.e(TAG, "Unsupported image format: $format. Attempting YUV conversion.")
                yuvToBitmap(imageProxy)
            }
        }
    }

    private fun rgbaToBitmap(imageProxy: ImageProxy): Bitmap {
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

        val finalBitmap = if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height)
            if (cropped != bitmap) bitmap.recycle()
            cropped
        } else {
            bitmap
        }

        return rotateBitmap(finalBitmap, imageProxy.imageInfo.rotationDegrees)
    }

    private fun yuvToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()

        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        return rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }
}
