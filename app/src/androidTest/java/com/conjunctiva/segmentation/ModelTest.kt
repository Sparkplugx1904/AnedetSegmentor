package com.conjunctiva.segmentation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test untuk memverifikasi model TFLite
 */
@RunWith(AndroidJUnit4::class)
class ModelTest {

    private lateinit var segmentor: ConjunctivaSegmentor
    private lateinit var testBitmap: Bitmap

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        segmentor = ConjunctivaSegmentor(context)
        
        // Create test bitmap (320x320 solid color)
        testBitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        testBitmap.eraseColor(Color.RED)
    }

    @Test
    fun testModelLoads() {
        // Model should load without exception
        assertNotNull(segmentor)
    }

    @Test
    fun testInferenceRuns() {
        // Inference should complete without crash
        val results = segmentor.segment(testBitmap)
        assertNotNull(results)
    }

    @Test
    fun testInferenceTime() {
        // Inference should complete in reasonable time
        val startTime = System.currentTimeMillis()
        segmentor.segment(testBitmap)
        val inferenceTime = System.currentTimeMillis() - startTime
        
        // Should be less than 500ms even on slow devices
        assertTrue("Inference too slow: ${inferenceTime}ms", inferenceTime < 500)
    }

    @Test
    fun testOutputFormat() {
        val results = segmentor.segment(testBitmap)
        
        // Results should be a list
        assertTrue(results is List)
        
        // Each result should have required fields
        results.forEach { result ->
            assertTrue(result.confidence >= 0f && result.confidence <= 1f)
            assertTrue(result.classId >= 0)
            assertNotNull(result.boundingBox)
            assertNotNull(result.polygon)
        }
    }

    @Test
    fun testDifferentImageSizes() {
        // Test with different input sizes
        val sizes = listOf(
            Pair(320, 320),
            Pair(640, 480),
            Pair(1280, 720)
        )
        
        sizes.forEach { (width, height) ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            
            val results = segmentor.segment(bitmap)
            assertNotNull("Failed for size ${width}x${height}", results)
        }
    }

    @Test
    fun testMultipleInferences() {
        // Test multiple consecutive inferences (memory leak check)
        repeat(10) {
            val results = segmentor.segment(testBitmap)
            assertNotNull(results)
        }
    }

    @Test
    fun testBoundingBoxCoordinates() {
        val results = segmentor.segment(testBitmap)
        
        results.forEach { result ->
            val box = result.boundingBox
            
            // Coordinates should be valid
            assertTrue("Invalid left coordinate", box.left >= 0)
            assertTrue("Invalid top coordinate", box.top >= 0)
            assertTrue("Right should be > left", box.right > box.left)
            assertTrue("Bottom should be > top", box.bottom > box.top)
        }
    }

    @Test
    fun testPolygonPoints() {
        val results = segmentor.segment(testBitmap)
        
        results.forEach { result ->
            val polygon = result.polygon
            
            // Polygon should have at least 3 points
            assertTrue("Polygon should have at least 3 points", polygon.size >= 3)
            
            // All points should have valid coordinates
            polygon.forEach { (x, y) ->
                assertTrue("Invalid X coordinate: $x", x >= 0)
                assertTrue("Invalid Y coordinate: $y", y >= 0)
            }
        }
    }
}
