package com.conjunctiva.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Class untuk menjalankan model segmentasi konjungtiva menggunakan TFLite FP16
 */
class ConjunctivaSegmentor(context: Context) {
    
    private var interpreter: Interpreter
    private val inputSize = 320 // Sesuai dengan imgsz saat export
    private val numChannels = 3
    private val pixelSize = numChannels
    
    // Buffer untuk input dan output
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var outputBoxes: Array<Array<FloatArray>>
    private lateinit var outputProtos: Array<Array<Array<FloatArray>>>
    
    companion object {
        private const val TAG = "ConjunctivaSegmentor"
        private const val MODEL_PATH = "models/best_float16.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.4f
        private const val IOU_THRESHOLD = 0.45f
    }

    init {
        // Load model dari assets atau file
        val modelFile = loadModelFile(context)
        
        // Konfigurasi interpreter dengan XNNPACK untuk performa optimal FP16 di CPU
        val options = Interpreter.Options().apply {
            // Aktifkan XNNPACK untuk performa optimal FP16 di CPU
            setUseXNNPACK(true)
            setNumThreads(4)
            
            // GPU delegate dihilangkan untuk menghindari masalah kompatibilitas
            // Jika ingin menggunakan GPU, gunakan TFLite GPU delegate versi terbaru
            Log.d(TAG, "Using CPU with XNNPACK acceleration")
        }
        
        interpreter = Interpreter(modelFile, options)
        
        // Log model input/output details
        val inputCount = interpreter.inputTensorCount
        val outputCount = interpreter.outputTensorCount
        Log.d(TAG, "Model has $inputCount inputs and $outputCount outputs")
        
        for (i in 0 until outputCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            val dataType = interpreter.getOutputTensor(i).dataType()
            Log.d(TAG, "Output $i: shape=${shape.contentToString()}, dataType=$dataType")
        }
        
        // Alokasi buffer
        allocateBuffers()
        
        Log.d(TAG, "Model loaded successfully. Input shape: [1, $inputSize, $inputSize, $numChannels]")
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        // Coba load dari folder models di root project
        val modelFile = context.filesDir.parentFile?.parentFile?.parentFile?.resolve(MODEL_PATH)
        
        return if (modelFile?.exists() == true) {
            Log.d(TAG, "Loading model from: ${modelFile.absolutePath}")
            FileInputStream(modelFile).use { inputStream ->
                val fileChannel = inputStream.channel
                fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
            }
        } else {
            // Fallback: coba dari assets
            Log.d(TAG, "Loading model from assets")
            val assetFileDescriptor = context.assets.openFd("best_float16.tflite")
            FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                val startOffset = assetFileDescriptor.startOffset
                val declaredLength = assetFileDescriptor.declaredLength
                fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            }
        }
    }

    private fun allocateBuffers() {
        // Input buffer: [1, 320, 320, 3] dengan FP32 (4 bytes per float)
        val inputBufferSize = 1 * inputSize * inputSize * numChannels * 4
        inputBuffer = ByteBuffer.allocateDirect(inputBufferSize).apply {
            order(ByteOrder.nativeOrder())
        }
        
        // Output 0: Detections [1, 300, 38]
        outputBoxes = Array(1) { Array(300) { FloatArray(38) } }
        
        // Output 1: Proto masks [1, 80, 80, 32]
        outputProtos = Array(1) { Array(80) { Array(80) { FloatArray(32) } } }
    }

    /**
     * Jalankan segmentasi pada bitmap
     */
    fun segment(bitmap: Bitmap): List<SegmentationResult> {
        // 1. Preprocessing
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        preprocessImage(resizedBitmap)
        
        // 2. Inference
        val startTime = System.currentTimeMillis()
        runInference()
        val inferenceTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "Inference time: ${inferenceTime}ms")
        
        // 3. Postprocessing
        val results = postprocess(bitmap.width, bitmap.height)
        
        return results
    }

    private fun preprocessImage(bitmap: Bitmap) {
        inputBuffer.rewind()
        
        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        // Normalisasi ke [0, 1] untuk FP16
        for (pixelValue in intValues) {
            val r = ((pixelValue shr 16) and 0xFF) / 255.0f
            val g = ((pixelValue shr 8) and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f
            
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }
    }

    private fun runInference() {
        // Jalankan model
        val outputs = mutableMapOf<Int, Any>()
        outputs[0] = outputBoxes
        outputs[1] = outputProtos
        
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
        
        // Debug: Log first detection values (all 38 values)
        val firstDetection = outputBoxes[0][0]
        val values = firstDetection.take(10).joinToString(", ") { "%.4f".format(it) }
        Log.d(TAG, "First 10 values: $values")
        
        // Check if there are any non-zero values
        val nonZeroCount = firstDetection.count { it != 0f }
        val maxValue = firstDetection.maxOrNull() ?: 0f
        Log.d(TAG, "Non-zero values: $nonZeroCount/38, max value: $maxValue")
    }

    private fun postprocess(originalWidth: Int, originalHeight: Int): List<SegmentationResult> {
        val results = mutableListOf<SegmentationResult>()
        
        // Parse output: [1, 300, 38]
        val detections = outputBoxes[0]
        val protos = outputProtos[0] // [80, 80, 32]
        val numDetections = 300
        
        var maxConfidence = 0f
        var validDetections = 0
        
        for (i in 0 until numDetections) {
            val detection = detections[i]
            
            // Get bbox coordinates (normalized 0-1)
            val xCenter = detection[0]
            val yCenter = detection[1]
            val width = detection[2]
            val height = detection[3]
            
            // Get objectness score (use as confidence for single-class model)
            val confidence = detection[4]
            
            // Track max confidence
            if (confidence > maxConfidence) {
                maxConfidence = confidence
            }
            
            if (confidence > CONFIDENCE_THRESHOLD) {
                validDetections++
                
                // Get mask coefficients (indices 6-37, total 32 values)
                val maskCoeffs = FloatArray(32) { detection[6 + it] }
                
                // Generate mask from proto and coefficients
                val mask = generateMask(protos, maskCoeffs, 80, 80)
                
                // Extract polygon from mask
                val polygon = extractPolygonFromMask(
                    mask, 
                    xCenter, yCenter, width, height,
                    originalWidth, originalHeight
                )
                
                // Convert bbox to pixel coordinates
                val left = ((xCenter - width / 2) * originalWidth).coerceIn(0f, originalWidth.toFloat())
                val top = ((yCenter - height / 2) * originalHeight).coerceIn(0f, originalHeight.toFloat())
                val right = ((xCenter + width / 2) * originalWidth).coerceIn(0f, originalWidth.toFloat())
                val bottom = ((yCenter + height / 2) * originalHeight).coerceIn(0f, originalHeight.toFloat())
                
                results.add(
                    SegmentationResult(
                        confidence = confidence,
                        classId = 0,
                        boundingBox = BoundingBox(left, top, right, bottom),
                        polygon = polygon
                    )
                )
            }
        }
        
        Log.d(TAG, "Postprocess: maxConfidence=$maxConfidence, validDetections=$validDetections, threshold=$CONFIDENCE_THRESHOLD")
        
        return applyNMS(results)
    }
    
    private fun generateMask(protos: Array<Array<FloatArray>>, coeffs: FloatArray, maskH: Int, maskW: Int): Array<FloatArray> {
        // Multiply proto masks with coefficients and apply sigmoid
        val mask = Array(maskH) { FloatArray(maskW) }
        
        for (y in 0 until maskH) {
            for (x in 0 until maskW) {
                var sum = 0f
                for (c in 0 until 32) {
                    sum += protos[y][x][c] * coeffs[c]
                }
                // Apply sigmoid
                mask[y][x] = 1f / (1f + kotlin.math.exp(-sum))
            }
        }
        
        return mask
    }
    
    private fun extractPolygonFromMask(
        mask: Array<FloatArray>,
        xCenter: Float, yCenter: Float, width: Float, height: Float,
        originalWidth: Int, originalHeight: Int
    ): List<Pair<Float, Float>> {
        val maskH = mask.size
        val maskW = mask[0].size
        val threshold = 0.5f

        // 1. Determine the bounding box in mask coordinates (80x80)
        val leftMask = ((xCenter - width / 2) * maskW).toInt().coerceIn(0, maskW - 1)
        val topMask = ((yCenter - height / 2) * maskH).toInt().coerceIn(0, maskH - 1)
        val rightMask = ((xCenter + width / 2) * maskW).toInt().coerceIn(0, maskW - 1)
        val bottomMask = ((yCenter + height / 2) * maskH).toInt().coerceIn(0, maskH - 1)

        val leftPoints = mutableListOf<Pair<Float, Float>>()
        val rightPoints = mutableListOf<Pair<Float, Float>>()

        // 2. Scan each row within the bounding box to find the boundary points
        for (y in topMask..bottomMask) {
            var firstX = -1
            var lastX = -1

            for (x in leftMask..rightMask) {
                if (mask[y][x] > threshold) {
                    if (firstX == -1) firstX = x
                    lastX = x
                }
            }

            if (firstX != -1) {
                // Convert mask coordinates back to original image coordinates
                val imgY = (y.toFloat() / maskH) * originalHeight

                leftPoints.add(Pair((firstX.toFloat() / maskW) * originalWidth, imgY))
                if (firstX != lastX) {
                    rightPoints.add(Pair((lastX.toFloat() / maskW) * originalWidth, imgY))
                }
            }
        }

        // 3. Combine points into a single ordered loop to avoid zigzagging
        // Top-to-bottom for the left edge, then bottom-to-top for the right edge
        val combinedPolygon = mutableListOf<Pair<Float, Float>>()
        combinedPolygon.addAll(leftPoints)
        combinedPolygon.addAll(rightPoints.reversed())

        // If no points found, return bbox as fallback
        if (combinedPolygon.isEmpty()) {
            val left = ((xCenter - width / 2) * originalWidth).coerceIn(0f, originalWidth.toFloat())
            val top = ((yCenter - height / 2) * originalHeight).coerceIn(0f, originalHeight.toFloat())
            val right = ((xCenter + width / 2) * originalWidth).coerceIn(0f, originalWidth.toFloat())
            val bottom = ((yCenter + height / 2) * originalHeight).coerceIn(0f, originalHeight.toFloat())
            
            return listOf(
                Pair(left, top),
                Pair(right, top),
                Pair(right, bottom),
                Pair(left, bottom)
            )
        }
        
        return combinedPolygon
    }

    private fun applyNMS(results: List<SegmentationResult>): List<SegmentationResult> {
        if (results.isEmpty()) return emptyList()
        
        val sortedResults = results.sortedByDescending { it.confidence }
        val selectedResults = mutableListOf<SegmentationResult>()
        
        for (result in sortedResults) {
            var shouldSelect = true
            for (selectedResult in selectedResults) {
                val iou = calculateIoU(result.boundingBox, selectedResult.boundingBox)
                if (iou > IOU_THRESHOLD) {
                    shouldSelect = false
                    break
                }
            }
            if (shouldSelect) {
                selectedResults.add(result)
            }
        }
        
        return selectedResults
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)
        
        if (intersectionRight < intersectionLeft || intersectionBottom < intersectionTop) {
            return 0f
        }
        
        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea
        
        return intersectionArea / unionArea
    }

    fun close() {
        interpreter.close()
    }
}

/**
 * Data class untuk hasil segmentasi
 */
data class SegmentationResult(
    val confidence: Float,
    val classId: Int,
    val boundingBox: BoundingBox,
    val polygon: List<Pair<Float, Float>> // Titik-titik polygon untuk masking
)

data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
