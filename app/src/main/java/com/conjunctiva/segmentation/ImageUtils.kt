package com.conjunctiva.segmentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Utility class untuk konversi format gambar
 */
object ImageUtils {

    /**
     * Konversi ImageProxy dari CameraX ke Bitmap
     * Mendukung format RGBA_8888 dan YUV_420_888
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        return when (imageProxy.format) {
            ImageFormat.YUV_420_888 -> {
                // Konversi YUV ke Bitmap
                yuv420ToBitmap(imageProxy)
            }
            else -> {
                // Untuk RGBA_8888 atau format lain
                rgbaToBitmap(imageProxy)
            }
        }
    }

    /**
     * Konversi YUV_420_888 ke Bitmap
     */
    private fun yuv420ToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()
        
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        
        // Rotate jika perlu
        return rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
    }

    /**
     * Konversi RGBA_8888 ke Bitmap
     */
    private fun rgbaToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer: ByteBuffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        val bitmap = Bitmap.createBitmap(
            imageProxy.width,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        
        // Rotate jika perlu
        return rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
    }

    /**
     * Rotate bitmap sesuai orientasi kamera
     */
    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    /**
     * Resize bitmap dengan mempertahankan aspect ratio
     */
    fun resizeBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetAspectRatio = targetWidth.toFloat() / targetHeight.toFloat()
        
        val (newWidth, newHeight) = if (aspectRatio > targetAspectRatio) {
            Pair(targetWidth, (targetWidth / aspectRatio).toInt())
        } else {
            Pair((targetHeight * aspectRatio).toInt(), targetHeight)
        }
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Crop bitmap ke center square
     */
    fun cropCenterSquare(bitmap: Bitmap): Bitmap {
        val dimension = minOf(bitmap.width, bitmap.height)
        val xOffset = (bitmap.width - dimension) / 2
        val yOffset = (bitmap.height - dimension) / 2
        
        return Bitmap.createBitmap(bitmap, xOffset, yOffset, dimension, dimension)
    }
}
