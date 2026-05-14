package com.conjunctiva.segmentation

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.Ignore

/**
 * Unit test untuk ImageUtils
 * Note: These tests require Android framework and should be run as instrumented tests
 */
class ImageUtilsTest {

    @Ignore("Requires Android framework - should be instrumented test")
    @Test
    fun testResizeBitmap() {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val resized = ImageUtils.resizeBitmap(bitmap, 320, 320)
        
        assertTrue(resized.width <= 320)
        assertTrue(resized.height <= 320)
    }

    @Ignore("Requires Android framework - should be instrumented test")
    @Test
    fun testCropCenterSquare() {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val cropped = ImageUtils.cropCenterSquare(bitmap)
        
        assertEquals(cropped.width, cropped.height)
        assertEquals(480, cropped.width) // Should be min(640, 480)
    }

    @Ignore("Requires Android framework - should be instrumented test")
    @Test
    fun testCropCenterSquareAlreadySquare() {
        val bitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        val cropped = ImageUtils.cropCenterSquare(bitmap)
        
        assertEquals(500, cropped.width)
        assertEquals(500, cropped.height)
    }
}
